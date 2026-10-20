package io.github.anikethts.changeradar.cli;

import io.github.anikethts.changeradar.core.PolicyEngine;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(
    name = "policies",
    aliases = {"list-policies"},
    description = "List named blocking policies (`changeradar list-policies`).")
public final class PoliciesCommand implements Callable<Integer> {

  @Override
  public Integer call() {
    System.out.println("ChangeRadar policies (off until --policy or --policy-file enables them)");
    System.out.println();
    for (String id : PolicyEngine.KNOWN.stream().sorted().toList()) {
      System.out.println(id);
      System.out.println("  " + PolicyEngine.DESCRIPTIONS.getOrDefault(id, ""));
      System.out.println();
    }
    return 0;
  }
}
