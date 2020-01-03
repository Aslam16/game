package app.psych.game.controller;

import app.psych.game.Utils;
import app.psych.game.model.*;
import app.psych.game.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/game")
public class game {
    private static Set<Player> submittedPlayers;

    @Autowired
    private
    StatsRepository statsRepository;
    @Autowired
    private
    PlayerRepository playerRepository;
    @Autowired
    private
    QuestionRepository questionRepository;
    @Autowired
    private
    GameRepository gameRepository;
    @Autowired
    private PlayerAnswerRepository playerAnswerRepository;
    @Autowired
    private
    RoundRepository roundRepository;

    @GetMapping("/create/{pid}/{gm}/{nr}")
    public String createGame(@PathVariable(value = "pid") Long playerId,
                             @PathVariable(value = "gm") int gameMode,
                             @PathVariable(value = "nr") int numRounds) {
        Optional<Player> optionalPlayer = playerRepository.findById(playerId);
        Player player = optionalPlayer.get();
        GameMode mode = GameMode.IS_THIS_A_FACT;

        Game game = new Game();
        game.setNumRounds(numRounds);
        game.setLeader(player);
        game.setGameMode(mode);
        game.getPlayers().add(player);

        gameRepository.save(game);

        return "" + game.getId() + "-" + Utils.getSecretCodeFromId(game.getId());
    }

    @GetMapping("/create/{pid}/{gc}")
    public String joinGame(@PathVariable(value = "pid") Long playerId,
                           @PathVariable(value = "gc") String gameCode) {
        Optional<Game> optionalGame = gameRepository.findById(Utils.getGameIdFromSecretCode(gameCode));
        Game game = optionalGame.get();
        if (!game.getGameStatus().equals(GameStatus.JOINING)) {
            // throw some error
        }
        Optional<Player> optionalPlayer = playerRepository.findById(playerId);
        Player player = optionalPlayer.get();

        game.getPlayers().add(player);
        gameRepository.save(game);

        return "successfully joined";
    }

    // startGame - pid, gid/gc
    // pid is actually the leader of the current game
    // game has not already been started
    // the game has more than 1 players
    @GetMapping("/start_game/{pid}/{gid}")
    public String startGame(@PathVariable(value = "pid") Long playerId,
                            @PathVariable(value = "gid") Long gameId) {
        Optional<Game> optionalGame = gameRepository.findById(gameId);
        Game game = optionalGame.get();
        if (!game.getGameStatus().equals(GameStatus.JOINING)) {
            // throw some error
        }
        Optional<Player> optionalPlayer = playerRepository.findById(playerId);
        Player player = optionalPlayer.get();
        if (game.getLeader().getId() != player.getId()) {
            return "Only leader can start the game";
        }
        if (game.getPlayers().size() <= 1) {
            return "Need at least two players to start the game";
        }
        GameStatus gameStatus = GameStatus.IN_PROGRESS;
        game.setGameStatus(gameStatus);
        game.setCurrentRound(1);
        long numOfQuestions = questionRepository.count();
        if (numOfQuestions == 0L) {
            return "No questions available";
        }
        Random r = new Random();

        Long firstQuestionId = r.nextLong() % numOfQuestions + 1;
        Optional<Question> optionalQuestion = questionRepository.findById(firstQuestionId);
        if (optionalQuestion.isEmpty()) {
            return "question not available";// todo
        }
        Question question = optionalQuestion.get();
        Round round = new Round();
        round.setGame(game);
        round.setRoundNumber(1);
        round.setQuestion(question);
        roundRepository.save(round);
        game.getRounds().add(round);
        Map<Player, Stats> playerStatsMap = game.getPlayerStats();
        for (Player p : game.getPlayers()) {
            playerStatsMap.put(p, new Stats());
        }
        gameRepository.save(game);
        return "Game started";
    }

    // endGame - pid gid
    // make sure that you're the leader of the game
    @GetMapping("/end_game")
    public String endGame(@PathVariable(value = "pid") Long playerId,
                          @PathVariable(value = "gid") Long gameId) {
        Optional<Game> optionalGame = gameRepository.findById(gameId);
        Game game = optionalGame.get();
        if (!game.getGameStatus().equals(GameStatus.JOINING)) {
            // throw some error
        }
        Optional<Player> optionalPlayer = playerRepository.findById(playerId);
        Player player = optionalPlayer.get();
        if (game.getLeader().getId() != player.getId()) {
            return "Only leader can end the game";
        }
        Map<Player, Stats> playerStats = game.getPlayerStats();
        for (Player p : playerStats.keySet()) {
            Stats globalStats = p.getStats();
            Stats localStats = playerStats.get(p);
            globalStats.setCorrectAnswers(globalStats.getCorrectAnswers() + localStats.getCorrectAnswers());
            globalStats.setGotPsychedCount(globalStats.getGotPsychedCount() + localStats.getGotPsychedCount());
            globalStats.setPsychedOthersCount(globalStats.getPsychedOthersCount() + localStats.getPsychedOthersCount());
            statsRepository.save(globalStats);
        }
        game.setGameStatus(GameStatus.OVER);
        return "Game Success Fully closed";
    }


    // getGameState - gid
    // JSON - current round - game stats of each player
    // - current round state - submitting-answer, selecting-answers-round-over
    @GetMapping("/game_state/{gid}")
    public String getGameState(@PathVariable(value = "gid") Long gameId) {
        Optional<Game> optionalGame = gameRepository.findById(gameId);
        if (optionalGame.isEmpty()) {
            return "No such Game Exists";
        }
        Game game = optionalGame.get();

        Map<String, String> gameDetails = new HashMap<>();
        Map<String, String> player_stats = new HashMap<>();
        gameDetails.put("Game status", game.getGameStatus());
        gameDetails.put("Current round number", game.getCurrentRound());
        gameDetails.put("game status", game.getGameStatus());
        String round_status_str = "No round Available";
        List<Round> rounds = game.getRounds();
        if (rounds.size() != 0) {
            Round currentRound = rounds.get(rounds.size() - 1);

            switch (currentRound.getRoundStatus()) {
                case RoundStatus.OVER:
                    round_status_str = "Over";
                    break;
                case RoundStatus.SELECTING_ANSWERS:
                    round_status_str = "Selecting Answers";
                    break;
                case RoundStatus.SUBMITTING_ANSWERS:
                default:
                    round_status_str = "Submitting Answers";
                    break;
            }
        }
        Map<Player, Stats> playerStats = game.getPlayerStats();
        playerStats.forEach((player, stats) -> {
            player_stats.put(player.getName(), String.format("correctAnswers: %d, gotPsychedCount: %d, psychedOthersCount: %d", stats.getCorrectAnswers(), stats.getGotPsychedCount(), stats.getPsychedOthersCount()));
        });
        gameDetails.put("player_stats", player_stats.toString());
        gameDetails.put("Round status", round_status_str);
        return gameDetails.toString();
    }

    // submitAnswer - pid, gid, answer
    @GetMapping("/submit_answer/{pid}/{gid}/{answer}")
    public String submitAnswer(@PathVariable(value = "pid") Long playerId,
                               @PathVariable(value = "gid") Long gameId,
                               @PathVariable(value = "answer") String answer) {
        Optional<Game> optionalGame = gameRepository.findById(gameId);
        Game game = optionalGame.get();
        if (!game.getGameStatus().equals(GameStatus.IN_PROGRESS)) {
            // throw some error
        }
        Optional<Player> optionalPlayer = playerRepository.findById(playerId);
        Player player = optionalPlayer.get();
        // checking player belong to  game or not
        if (!game.getPlayers().contains(player)) {
            return "Current player dose not belong to current game";
        }
        List<Round> rounds = game.getRounds();
        Round currentRound = rounds.get(rounds.size() - 1);
        if (currentRound.getRoundStatus() != RoundStatus.SUBMITTING_ANSWERS) {
            return "Can't submit answers now";
        }
        Map<Player, PlayerAnswer> playerAnswerMap = currentRound.getPlayerAnswers();
        PlayerAnswer playerAnswer = new PlayerAnswer();
        for (Player p : playerAnswerMap.keySet()) {
            if (answer.equals(playerAnswerMap.get(p).getAnswer())) {
                return "Other player already chose the answer please select other answer";
            }
        }
        if (answer.equals(currentRound.getQuestion().getCorrectAnswer())) {
            return "Other player already chose the answer please select other answer";
        }
        playerAnswer.setPlayer(player);
        playerAnswer.setAnswer(answer);
        playerAnswer.setRound(currentRound);
        playerAnswerRepository.save(playerAnswer);
        if (playerAnswerMap.containsKey(player))
            playerAnswerRepository.delete(currentRound.getPlayerAnswers().get(player));
        playerAnswerMap.put(player, playerAnswer);
        roundRepository.save(currentRound);
        submittedPlayers.add(player);
        boolean is_completed = false;
        for(Player p:game.getPlayers()){
            if(!submittedPlayers.contains(p))
            {is_completed = true;break;}
        }
        if(is_completed){
            currentRound.setRoundStatus(RoundStatus.OVER);
        }
        return "Successfully submitted answer";
    }
    // leaveGame - pid, gid
    // update player's stats

    // selectAnswer - pid, gid, answer-id
    // check if the answer is right or not,
    // update the and the game stats
    // to detect if the game has ended, and to end the game.
    // when the game ends, update every players stats
    @GetMapping("/select_answer/{pid}/{gid}/{answer}")
    public String selectAnswer(@PathVariable(value = "pid") Long playerId,
                               @PathVariable(value = "gid") Long gameId,
                               @PathVariable(value = "answer") String answer) {
        Optional<Game> optionalGame = gameRepository.findById(gameId);
        Game game = optionalGame.get();
        if (!game.getGameStatus().equals(GameStatus.IN_PROGRESS)) {
            // throw some error
        }
        Optional<Player> optionalPlayer = playerRepository.findById(playerId);
        Player player = optionalPlayer.get();
        // checking player belong to  game or not
        if (!game.getPlayers().contains(player)) {
            return "Current player dose not belong to current game";
        }
        List<Round> rounds = game.getRounds();
        Round currentRound = rounds.get(rounds.size() - 1);
        if (currentRound.getRoundStatus() != RoundStatus.SUBMITTING_ANSWERS) {
            return "Can't submit answers now";
        }
        Map<Player, PlayerAnswer> playerAnswerMap = currentRound.getPlayerAnswers();
        Map<Player, Stats> playerStats = game.getPlayerStats();
        if (answer.equals(currentRound.getQuestion().getCorrectAnswer())) {
            Stats currentPlayerStats = playerStats.get(player);
            currentPlayerStats.setCorrectAnswers(currentPlayerStats.getCorrectAnswers() + 1);
        } else {
            for (Player p : playerAnswerMap.keySet()) {
                if (answer.equals(playerAnswerMap.get(p).getAnswer())) {
                    Stats otherPlayerStats = playerStats.get(p);
                    otherPlayerStats.setPsychedOthersCount(otherPlayerStats.getPsychedOthersCount() + 1);
                    Stats currentPlayerStats = playerStats.get(player);
                    currentPlayerStats.setGotPsychedCount(currentPlayerStats.getGotPsychedCount() + 1);
                    statsRepository.save(otherPlayerStats);
                }
            }
        }
        statsRepository.save(currentPlayerStats);

    }


    // getReady - pid, gid
    @GetMapping("/get_ready_round/{pid}/{gid}")
    public String readyGameRound(@PathVariable(value = "pid") Long playerId,
                                 @PathVariable(value = "gid") Long gameId) {
        Optional<Game> optionalGame = gameRepository.findById(gameId);
        Game game = optionalGame.get();
        if (!game.getGameStatus().equals(GameStatus.JOINING)) {
            // throw some error
        }
        Optional<Player> optionalPlayer = playerRepository.findById(playerId);
        Player player = optionalPlayer.get();
        if (game.getLeader().getId() != player.getId()) {
            return "Only leader can change round status of the game";
        }
        List<Round> rounds = game.getRounds();

        Round currentRound = rounds.get(rounds.size() - 1);
        if (currentRound.getRoundStatus() != RoundStatus.SUBMITTING_ANSWERS) {
            return "Can't change round status now";
        }
        if (game.getPlayers().size() > currentRound.getPlayerAnswers().size()) {
            return "not all players submitted answers";
        }
        currentRound.setRoundStatus(RoundStatus.SELECTING_ANSWERS);
        List<String> allAnswers = new ArrayList<>();
        Map<Player, PlayerAnswer> playerAnswers = currentRound.getPlayerAnswers();
        playerAnswers.forEach((gamePlayer, playerAnswer) -> {
            allAnswers.add(playerAnswer.getAnswer());
        });
        allAnswers.add(currentRound.getQuestion().getCorrectAnswer());
        return allAnswers.toString();
    }
}


// pragy@interviewbit.com

