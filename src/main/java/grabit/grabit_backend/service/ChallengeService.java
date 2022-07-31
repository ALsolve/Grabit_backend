package grabit.grabit_backend.service;

import grabit.grabit_backend.domain.Challenge;
import grabit.grabit_backend.domain.JoinChallengeRequest;
import grabit.grabit_backend.domain.User;
import grabit.grabit_backend.domain.UserChallenge;
import grabit.grabit_backend.dto.CreateChallengeDTO;
import grabit.grabit_backend.dto.ModifyChallengeDTO;
import grabit.grabit_backend.exception.BadRequestException;
import grabit.grabit_backend.exception.ForbiddenException;
import grabit.grabit_backend.exception.NotFoundException;
import grabit.grabit_backend.exception.UnauthorizedException;
import grabit.grabit_backend.repository.ChallengeRepository;
import grabit.grabit_backend.repository.JoinChallengeRequestRepository;
import grabit.grabit_backend.repository.UserChallengeRepository;
import grabit.grabit_backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class ChallengeService {

	private final ChallengeRepository challengeRepository;
	private final UserChallengeRepository userChallengeRepository;
	private final UserRepository userRepository;
	private final JoinChallengeRequestRepository joinChallengeRequestRepository;

	@Autowired
	public ChallengeService(ChallengeRepository challengeRepository,
							UserChallengeRepository userChallengeRepository,
							UserRepository userRepository,
							JoinChallengeRequestRepository joinChallengeRequestRepository){
		this.challengeRepository = challengeRepository;
		this.userChallengeRepository = userChallengeRepository;
		this.userRepository = userRepository;
		this.joinChallengeRequestRepository = joinChallengeRequestRepository;
	}

	/**
	 * 챌린지 생성
	 * @param createChallengeDTO
	 * @return id
	 */
	@Transactional
	public Challenge createChallenge(CreateChallengeDTO createChallengeDTO, User user){
		Challenge challenge = Challenge.createChallenge(createChallengeDTO, user);
		Challenge createChallenge = challengeRepository.save(challenge);

		UserChallenge userChallenge = UserChallenge.createUserChallenge(challenge, user);
		userChallengeRepository.save(userChallenge);

		List<UserChallenge> userChallengeList = new ArrayList<>();
		userChallengeList.add(userChallenge);
		createChallenge.setUserChallengeList(userChallengeList);

		return createChallenge;
	}

	/**
	 * 챌린지 조회 (id)
	 * @param id
	 * @return Challenge
	 */
	@Transactional
	public Challenge findChallengeById(Long id){
		Optional<Challenge> findChallenge = challengeRepository.findChallengeById(id);
		if(findChallenge.isEmpty()){
			throw new NotFoundException("존재하지 않는 챌린지입니다..");
		}
		return findChallenge.get();
	}

	/**
	 * 챌린지 삭제
	 * @param id
	 */
	@Transactional
	public void deleteChallengeById(Long id, User user){
		Challenge findChallenge = findChallengeById(id);

		// leader 여부 확인.
		if(!findChallenge.getLeader().getUserId().equals(user.getUserId())){
			throw new UnauthorizedException();
		}

		challengeRepository.deleteById(id);
	}

	/**
	 * 챌린지 수정
	 * @param id
	 * @param modifyChallengeDTO
	 * @return Challenge
	 */
	@Transactional
	public Challenge updateChallenge(Long id, ModifyChallengeDTO modifyChallengeDTO, User user){
		Challenge findChallenge = findChallengeById(id);

		// leader 여부 확인.
		if(!findChallenge.getLeader().getId().equals(user.getId())){
			throw new UnauthorizedException();
		}

		Optional<User> leader = userRepository.findByUserId(modifyChallengeDTO.getLeader());
		if(leader.isEmpty()){
			throw new IllegalStateException("존재하지 않는 유저입니다.");
		}

		User findLeader = leader.get();
		findChallenge.modifyChallenge(modifyChallengeDTO, findLeader);
		Challenge modifiedChallenge = challengeRepository.save(findChallenge);

		return modifiedChallenge;
	}

	/**
	 * 챌린지 조회 with Paging
	 * @param page
	 * @param size
	 * @return
	 */
	@Transactional
	public Page<Challenge> findChallengeBySearchWithPage(String title, String description, String leaderId, Integer page, Integer size){
		PageRequest pageRequest = PageRequest.of(page, size);
		if (leaderId == null && title == null && description == null){
			throw new IllegalStateException("잘못된 요청입니다.");
		}
		return challengeRepository.findChallengeBySearchWithPaging(pageRequest, title, description, leaderId);
	}

	/**
	 * 챌린지 가입
	 * @param id
	 * @param user
	 * @return
	 */
	@Transactional
	public Challenge requestJoinChallenge(Long id, User user){
		Challenge challenge = findChallengeById(id);

		Optional<UserChallenge> findUserChallenge = userChallengeRepository.findByUserAndChallenge(user, challenge);
		if(findUserChallenge.isPresent()){
			throw new BadRequestException("이미 가입한 유저입니다.");
		}

		if (challenge.getIsPrivate()) {
			JoinChallengeRequest joinChallengeRequest = JoinChallengeRequest.createJoinChallengeRequest(challenge, user);
			this.joinChallengeRequestRepository.save(joinChallengeRequest);
		} else {
			joinChallenge(challenge, user);
		}
		return challenge;
	}

	private void joinChallenge(Challenge challenge, User user) {
		UserChallenge userChallenge = UserChallenge.createUserChallenge(challenge, user);
		userChallengeRepository.save(userChallenge);
		challenge.getUserChallengeList().add(userChallenge);
	}

	/**
	 * 챌린지 가입 승인
	 */
	public Challenge approveJoinChallengeRequest(Long joinChallengeRequestId, User leader) {
		JoinChallengeRequest joinChallengeRequest = findJoinChallengeRequestById(joinChallengeRequestId);
		Challenge challenge = joinChallengeRequest.getChallenge();

		checkIsLeader(leader, challenge);

		this.joinChallengeRequestRepository.delete(joinChallengeRequest);

		joinChallenge(challenge, joinChallengeRequest.getUser());
		return challenge;
	}

	/**
	 * 챌린지 가입 거절
	 * @param user
	 * @param joinChallengeRequestId
	 */
	public void rejectJoinChallengeRequest(Long joinChallengeRequestId, User user) {
		JoinChallengeRequest joinChallengeRequest = findJoinChallengeRequestById(joinChallengeRequestId);
		Challenge challenge = joinChallengeRequest.getChallenge();

		checkIsLeader(user, challenge);

		this.joinChallengeRequestRepository.delete(joinChallengeRequest);
	}

	/**
	 * 챌린지 탈퇴
	 * @param id
	 * @param user
	 * @return
	 */
	@Transactional
	public void leaveChallenge(Long id, User user){
		Challenge findChallenge = findChallengeById(id);
		userChallengeRepository.deleteByUserAndChallenge(user, findChallenge);
	}

	/**
	 * 챌린지 가입 요청 목록
	 */
	public Page<JoinChallengeRequest> findJoinChallengeRequestListByChallengeWithPage(User user, Long challengeId, Integer page, Integer size) {
		Challenge challenge = findChallengeById(challengeId);

		checkIsLeader(user, challenge);
		PageRequest pageRequest = PageRequest.of(page, size);
		Page<JoinChallengeRequest> requestList = this.joinChallengeRequestRepository.findJoinChallengeRequestByChallengeWithPage(pageRequest, challenge);
		return requestList;
	}

	private void checkIsLeader(User user, Challenge challenge) {
		if (!challenge.getLeader().getId().equals(user.getId())) {
			throw new ForbiddenException("권한이 없습니다.");
		}
	}

	public JoinChallengeRequest findJoinChallengeRequestById(Long joinChallengeRequestId) throws NotFoundException {
		Optional<JoinChallengeRequest> request = this.joinChallengeRequestRepository.findById(joinChallengeRequestId);
		if (request.isEmpty()) {
			throw new NotFoundException();
		}
		return request.get();
	}
}

