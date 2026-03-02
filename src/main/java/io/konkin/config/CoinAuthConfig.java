package io.konkin.config;

import java.util.List;

public record CoinAuthConfig(
        List<ApprovalRule> autoAccept,
        List<ApprovalRule> autoDeny,
        boolean webUi,
        boolean restApi,
        boolean telegram,
        String mcp,
        List<String> mcpAuthChannels,
        int minApprovalsRequired,
        List<String> vetoChannels
) {
}
