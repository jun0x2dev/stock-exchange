package com.exchange.domain.audit.service.impl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.exchange.domain.audit.dto.AuditVerification;
import com.exchange.domain.audit.entity.AuditChain;
import com.exchange.domain.audit.entity.AuditLog;
import com.exchange.domain.audit.repository.AuditChainRepository;
import com.exchange.domain.audit.repository.AuditChainRepository.Link;
import com.exchange.domain.audit.service.AuditChainService;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;

/** 감사 로그 체인 구현. DB 밖에 남기는 값은 한 줄에 "번호 해시"로 적는다. */
@Slf4j
@Service
@Transactional(readOnly = true)
public class AuditChainServiceImpl implements AuditChainService {

	private static final int BATCH = 500;
	private static final int MAX_PROBLEMS = 20;
	private static final Pattern ANCHOR_LINE = Pattern.compile("\\d{1,18} [0-9a-f]{64}");

	/** 이보다 오래 체인에 들어가지 않은 로그는 문제로 본다. */
	private static final int UNCHAINED_SECONDS = 60;

	private final AuditChainRepository chainRepository;
	private final AuditHasher hasher;
	private final EntityManager entityManager;
	private final Path anchorFile;

	/** 체인 저장소와 키, DB 밖 파일의 위치를 받는다. 위치가 비어 있으면 남기지 않는다. */
	public AuditChainServiceImpl(AuditChainRepository chainRepository, AuditHasher hasher, EntityManager entityManager,
			@Value("${exchange.audit.anchor-file}") String anchorFile) {
		this.chainRepository = chainRepository;
		this.hasher = hasher;
		this.entityManager = entityManager;
		this.anchorFile = StringUtils.hasText(anchorFile) ? Path.of(anchorFile) : null;
	}

	@Override
	@Transactional
	public int extend() {
		if (!chainRepository.tryLock()) {
			return 0;
		}
		List<AuditLog> pending = chainRepository.findPending(BATCH);
		if (pending.isEmpty()) {
			return 0;
		}
		Optional<AuditChain> last = chainRepository.findFirstByOrderByChainSeqDesc();
		long seq = last.map(AuditChain::getChainSeq).orElse(0L);
		String hash = last.map(AuditChain::getHash).orElse(AuditHasher.GENESIS);
		for (AuditLog entry : pending) {
			String next = hasher.hash(hash, entry);
			chainRepository.save(AuditChain.of(++seq, entry.getAuditId(), hash, next));
			hash = next;
		}
		chainRepository.deletePending(pending.stream().map(AuditLog::getAuditId).toList());
		return pending.size();
	}

	@Override
	public void anchor() {
		if (anchorFile == null) {
			return;
		}
		chainRepository.findFirstByOrderByChainSeqDesc().ifPresent(last -> {
			List<String> lines = readAnchors();
			// 체인이 짧아졌거나 그대로면 적지 않는다. 짧아진 것은 검증이 찾아낸다.
			if (!lines.isEmpty() && Long.parseLong(lines.getLast().split(" ")[0]) >= last.getChainSeq()) {
				return;
			}
			try {
				Files.writeString(anchorFile, last.getChainSeq() + " " + last.getHash() + System.lineSeparator(),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	// 검증하는 동안 체인이 늘어나도 한 시점의 기록만 보도록 격리 수준을 높인다.
	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public AuditVerification verify() {
		List<String> problems = new ArrayList<>();
		// DB보다 먼저 읽어야, 그 사이 남긴 값을 지워진 것으로 오해하지 않는다.
		Map<Long, String> anchors = parseAnchors(problems);
		int anchorCount = anchors.size();
		long seq = 0;
		String hash = AuditHasher.GENESIS;
		List<Link> links;
		while (!(links = chainRepository.findAfter(seq, Limit.of(BATCH))).isEmpty()) {
			for (Link link : links) {
				AuditChain chain = link.chain();
				if (chain.getChainSeq() != seq + 1) {
					add(problems, "체인 번호 " + (seq + 1) + "부터 " + (chain.getChainSeq() - 1) + "까지 없음");
				}
				if (!chain.getPrevHash().equals(hash)) {
					add(problems, "체인 " + chain.getChainSeq() + "이 앞과 이어지지 않음");
				}
				if (link.log() == null) {
					add(problems, "감사 로그 " + chain.getAuditId() + "이 지워짐");
				} else if (!hasher.hash(chain.getPrevHash(), link.log()).equals(chain.getHash())) {
					add(problems, "감사 로그 " + chain.getAuditId() + "의 내용이 기록 당시와 다름");
				}
				seq = chain.getChainSeq();
				hash = chain.getHash();
				String anchored = anchors.remove(seq);
				if (anchored != null && !anchored.equals(hash)) {
					add(problems, "DB 밖에 남긴 체인 " + seq + "의 값과 다름");
				}
			}
			// 읽은 행이 영속성 컨텍스트에 쌓이지 않게 한다.
			entityManager.clear();
		}
		anchors.keySet().forEach(missing -> add(problems, "DB 밖에 남긴 체인 " + missing + "이 지워짐"));
		long unchained = chainRepository.countUnchained(UNCHAINED_SECONDS);
		if (unchained > 0) {
			add(problems, "체인에 들어가지 않은 감사 로그 " + unchained + "건");
		}
		if (!problems.isEmpty()) {
			log.error("감사 로그 검증 실패: {}", problems);
		}
		return new AuditVerification(problems.isEmpty(), seq, anchorCount, List.copyOf(problems));
	}

	// 문제가 많아도 응답이 커지지 않게 일부만 담는다.
	private static void add(List<String> problems, String problem) {
		if (problems.size() < MAX_PROBLEMS) {
			problems.add(problem);
		}
	}

	// 번호별 해시로 바꾼다. 형식이 깨진 줄은 문제로 남긴다.
	private Map<Long, String> parseAnchors(List<String> problems) {
		Map<Long, String> anchors = new TreeMap<>();
		for (String line : readAnchors()) {
			if (ANCHOR_LINE.matcher(line).matches()) {
				String[] parts = line.split(" ");
				anchors.put(Long.valueOf(parts[0]), parts[1]);
			} else {
				add(problems, "DB 밖에 남긴 파일의 형식이 깨짐");
			}
		}
		return anchors;
	}

	// DB 밖 파일의 모든 줄. 아직 없으면 비어 있다.
	// ponytail: 1분에 한 줄이라 통째로 읽는다. 해가 쌓이면 파일을 기간별로 나눈다.
	private List<String> readAnchors() {
		if (anchorFile == null || Files.notExists(anchorFile)) {
			return List.of();
		}
		try {
			return Files.readAllLines(anchorFile).stream().filter(StringUtils::hasText).toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
