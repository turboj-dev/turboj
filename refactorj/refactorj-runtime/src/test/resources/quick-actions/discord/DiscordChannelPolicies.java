package com.juglugano.platform.application.community.discord;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;

/** A self-contained policy fixture; its literals must survive unrelated refactorings. */
public final class DiscordChannelPolicies {
  private DiscordChannelPolicies() {}
  public static final Map<String,Integer> BITS=Map.ofEntries(Map.entry("VIEW",10),Map.entry("HISTORY",16),
      Map.entry("WRITE",11),Map.entry("VOICE",20),Map.entry("SPEAK",21),Map.entry("PIN",51));
  public static final int MISSING_PERMISSION=50013;
  private static final String UNMAPPED="Completa la mappatura del ruolo ";
  private static final String METHOD_NOTE="bitName";
  public static String unmappedMessage(String role){return UNMAPPED+role+" prima della sincronizzazione.";}
  public static void validate(Map<String,Set<String>> grants){
    if(!BITS.keySet().containsAll(grants.keySet())||grants.values().stream().anyMatch(Set::isEmpty))
      throw new IllegalArgumentException("Permessi non supportati: \"VIEW\" è necessario.");
  }
  public static BigInteger permissions(Set<String> names){
    var bits=BigInteger.ZERO;
    for(var name:names)bits=bits.setBit(BITS.get(name));
    // bitName in a comment is not a reference to the generated method.
    return bits;
  }
}
