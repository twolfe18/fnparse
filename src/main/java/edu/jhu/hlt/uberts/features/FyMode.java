package edu.jhu.hlt.uberts.features;

import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.srl.EdgeUtils;

public enum FyMode {
  // just use null
//  NONE { String[] f(HypEdge e) { return new String[] {}; } },
  CONST { public String[] f(HypEdge e) { return new String[] {
      "const",
  }; } },
  K_F { public String[] f(HypEdge e) { return new String[] {
      EdgeUtils.role(e),
      EdgeUtils.frame(e),
  }; } },
  K_F_1 { public String[] f(HypEdge e) { return new String[] {
      EdgeUtils.role(e),
      EdgeUtils.frame(e),
      "const",
  }; } },
  K_FK_1 { public String[] f(HypEdge e) { return new String[] {
      EdgeUtils.role(e),
      EdgeUtils.frame(e) + "-" + EdgeUtils.role(e),
      "const",
  }; } },
  F_FK_1 { public String[] f(HypEdge e) { return new String[] {
      EdgeUtils.frame(e),
      EdgeUtils.frame(e) + "-" + EdgeUtils.role(e),
      "const",
  }; } },
  K_FK_F_1 { public String[] f(HypEdge e) { return new String[] {
      EdgeUtils.role(e),
      EdgeUtils.frame(e) + "-" + EdgeUtils.role(e),
      EdgeUtils.frame(e),
      "const",
  }; } },
  K_FK { public String[] f(HypEdge e) { return new String[] {
      EdgeUtils.role(e),
      EdgeUtils.frame(e) + "-" + EdgeUtils.role(e),
  }; } },
  K_1 { public String[] f(HypEdge e) { return new String[] {
      EdgeUtils.role(e),
      "const",
  }; } },
  K { public String[] f(HypEdge e) { return new String[] {
      EdgeUtils.role(e),
  }; } },
  F { public String[] f(HypEdge e) { return new String[] {
      EdgeUtils.frame(e),
  }; } },
  F_1 { public String[] f(HypEdge e) { return new String[] {
      EdgeUtils.frame(e),
      "const",
  }; } },
  FK { public String[] f(HypEdge e) { return new String[] {
      EdgeUtils.frame(e) + "-" + EdgeUtils.role(e),
  }; } },
  FK_F { public String[] f(HypEdge e) { return new String[] {
      EdgeUtils.frame(e) + "-" + EdgeUtils.role(e),
      EdgeUtils.frame(e),
  }; } },
  FK_1 { public String[] f(HypEdge e) { return new String[] {
      EdgeUtils.frame(e) + "-" + EdgeUtils.role(e),
      "const",
  }; } };
  public abstract String[] f(HypEdge e);
}
