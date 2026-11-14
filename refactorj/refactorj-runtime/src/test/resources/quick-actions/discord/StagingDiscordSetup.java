/** An unrelated source in the workspace must remain byte-for-byte unchanged. */
public final class StagingDiscordSetup {
  private static final String CALLBACK="https://example.invalid/api/community/discord/callback";
  private static final long GUILD=1234567890123456789L;
  private static final int MISSING_PERMISSION=50013;
  private static final String NOTE="bitName";
  public static String describe(){return CALLBACK+" #"+GUILD+" "+MISSING_PERMISSION+" "+NOTE;}
}
