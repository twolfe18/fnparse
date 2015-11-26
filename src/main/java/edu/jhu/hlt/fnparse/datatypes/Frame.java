package edu.jhu.hlt.fnparse.datatypes;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.tutils.data.PropbankFrameIndex.PropbankFrame;

public class Frame implements Serializable {
  private static final long serialVersionUID = 8807170122216944787L;

  private int idx;
  private String name;                 // e.g. "Commerce_buy"
  private LexicalUnit[] lexicalUnits;  // e.g. ["purchase.v", "buy.v"]
  private String[] roles;              // e.g. ["Buyer", "Goods"]
  private String[] roleTypes;          // e.g. ["Core", "Core"]

  public Frame(
      int id,
      String name,
      LexicalUnit[] lexicalUnits,
      String[] roles) {
    if(roles == null || roles.length == 0)
      throw new IllegalArgumentException();
    this.idx = id;
    this.name = name;
    this.lexicalUnits = lexicalUnits;
    this.roles = roles;
  }

  /**
   * @param modifierRoles is a list of all the possible modifier roles, e.g.
   * "ARGM-LOC", "ARGM-TMP", etc. PropbankFrames do not include these.
   *
   * @param universalRoles: if true, we will ignore the roles described in the
   * given {@link PropbankFrame} in favor of a fixed list of ARG0...ARG5...etc.
   * The reason to do this is that there are some mis-matches between the
   * CoNLL-formatted instance data and the types listed in the frame index XML
   * (e.g. instance data will point to ARG2 and the frame index doesn't have an
   *  ARG2 for that frame...).
   * Another benefit of using universalRoles is that you never have to worry
   * about frame_i: {k=0 => ARG1 ...} and frame_j: {k=0 => ARG0 ...}, that is
   * this makes k truely independent of t/frame.
   */
  public Frame(PropbankFrame pf, int idx, List<String> modifierRoles, boolean universalRoles) {
    this.idx = idx;
    this.name = pf.id;
    this.lexicalUnits = null;

    Set<String> seenRoles = new HashSet<>();
    List<String> allRoles = new ArrayList<>();
    List<String> allRoleTypes = new ArrayList<>();

    int i = 0;
    if (universalRoles) {
      allRoles.add("ARG0"); allRoleTypes.add("core");
      allRoles.add("ARG1"); allRoleTypes.add("core");
      allRoles.add("ARG2"); allRoleTypes.add("core");
      allRoles.add("ARG3"); allRoleTypes.add("core");
      allRoles.add("ARG4"); allRoleTypes.add("core");
      allRoles.add("ARG5"); allRoleTypes.add("core");
      allRoles.add("ARGA"); allRoleTypes.add("adj");
      seenRoles.addAll(allRoles);
    } else {
      for (; i < pf.numRoles(); i++) {
        String r = pf.getRole(i).getLabel();
        allRoles.add(r);
        allRoleTypes.add("core");
        System.out.println("adding core role " + r + " to " + pf.name);
        if (!seenRoles.add(r))
          throw new RuntimeException("duplicate roles: " + pf);
      }
    }

    for (String mr : modifierRoles) {
      // Sometimes the modifier roles are included in the frame index,
      // e.g. ARGM-LOC in add-v-3
      if (seenRoles.add(mr)) {
        allRoles.add(mr);
        allRoleTypes.add("modifier");
        i++;
      }
    }
    this.roles = new String[allRoles.size()];
    this.roleTypes = new String[allRoles.size()];
    for (int j = 0; j < allRoles.size(); j++) {
      this.roles[j] = allRoles.get(j);
      this.roleTypes[j] = allRoleTypes.get(j);
    }
  }

  private Frame() {
    this.idx = 0;
    this.name = "NOT_A_FRAME";
    this.lexicalUnits = new LexicalUnit[0];
    this.roles = new String[0];
  }

  public String toString() {
    return String.format("<Frame %d %s has %d roles>", idx, name, numRoles());
  }

  public int getId() { return idx; }

  public void setRoleType(int role, String type) {
    if (roleTypes == null)
      roleTypes = new String[roles.length];
    roleTypes[role] = type;
  }

  /**
   * e.g. "Core", "Core-Unexpressed", "Extra-Thematic", or "Extra-Thematic"
   */
  public String getRoleType(int role) {
    return roleTypes[role];
  }

  public LexicalUnit getLexicalUnit(int i) {
    return lexicalUnits[i];
  }

  public int numLexicalUnits() {
    if (this == nullFrame)
      return 0;
    return lexicalUnits.length;
  }

  public String getRole(int i) {
    return roles[i];
  }

  public String getRoleSafe(int i) {
    if (i < roles.length)
      return roles[i];
    return "<not-a-role>";
  }

  public int numRoles() {
    if (this == nullFrame)
      return 0;
    return roles.length;
  }

  public String[] getRoles() {
    return roles;
  }

  public String getName() {
    return name;
  }

  @Override
  public int hashCode() {
    return idx * 3571;
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof Frame) {
      Frame f = (Frame) other;
      return idx == f.idx;
    }
    else return false;
  }

  /**
   * Frame used to indicate that a word does not evoke a frame
   */
  public static final Frame nullFrame = new Frame();
}
