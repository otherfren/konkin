package io.konkin.db.entity;

import java.util.List;

public record RequestDependencies(
        List<StateTransitionDetail> transitions,
        List<RequestChannelDetail> channels,
        List<VoteDetail> votes,
        List<ExecutionAttemptDetail> executionAttempts
) {
}
