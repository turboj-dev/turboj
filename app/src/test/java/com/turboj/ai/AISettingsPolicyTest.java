package com.turboj.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AISettingsPolicyTest {
    private AIConfig cloud(String endpoint, String model, boolean automatic, boolean consent) {
        AIConfig initial = AIConfig.defaults();
        return new AIConfig(
                AIConfig.Mode.CLOUD,
                initial.localBackend(),
                initial.localEndpoint(),
                initial.localModel(),
                endpoint,
                model,
                true,
                automatic,
                consent,
                200,
                30,
                256,
                12_288,
                "*.key",
                false);
    }

    @Test
    void newCloudModelEndpointOrAutomaticPolicyNeedsExplicitConfirmation() {
        AIConfig initial = cloud("https://api.openai.com/v1", "model-a", false, true);
        assertFalse(AISettingsDialog.needsCloudConfirmation(initial, initial));
        assertTrue(AISettingsDialog.needsCloudConfirmation(AIConfig.defaults(), initial));
        assertTrue(
                AISettingsDialog.needsCloudConfirmation(
                        initial, cloud("https://other.test/v1", "model-a", false, true)));
        assertTrue(
                AISettingsDialog.needsCloudConfirmation(
                        initial, cloud(initial.cloudEndpoint(), "model-b", false, true)));
        assertTrue(
                AISettingsDialog.needsCloudConfirmation(
                        initial, cloud(initial.cloudEndpoint(), "model-a", true, true)));
        assertTrue(
                AISettingsDialog.needsCloudConfirmation(
                        initial.withMode(AIConfig.Mode.LOCAL), initial));
        assertFalse(
                AISettingsDialog.needsCloudConfirmation(
                        initial, initial.withMode(AIConfig.Mode.DISABLED)));
    }

    @Test
    void EquivalentEndpointFormattingDoesNotInventANewDestination() {
        AIConfig initial = cloud("https://api.openai.com/v1", "model-a", false, true);
        assertFalse(
                AISettingsDialog.needsCloudConfirmation(
                        initial, cloud("https://API.OPENAI.COM:443/v1/", "model-a", false, true)));
    }
}
