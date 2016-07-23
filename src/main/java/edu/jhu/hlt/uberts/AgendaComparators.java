package edu.jhu.hlt.uberts;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.uberts.Agenda.AgendaItem;
import edu.jhu.hlt.uberts.srl.EdgeUtils;

public class AgendaComparators {

  public static Comparator<AgendaItem> getPriority(ExperimentProperties config) {
    String[] toks = config.getStrings("agendaComparator");
    Log.info("[main] agendaComparator=" + StringUtils.join(",", toks));

    Set<String> uniq = new HashSet<>();
    for (String i : toks)
      assert uniq.add(i) : "duplicate comparator: " + i + " in " + Arrays.toString(toks);

    Comparator<AgendaItem> c = parse(toks[0]);
    assert c == BY_RELATION : "you probably want to do this first";

    boolean byScore = false;
    for (int i = 1; i < toks.length; i++) {
      Comparator<AgendaItem> ci = parse(toks[i]);
      byScore |= ci == BY_SCORE;
      c = c.thenComparing(ci);
    }
    assert byScore : "not sorting by score? " + Arrays.toString(toks);
    return c;
  }

  @SuppressWarnings("unchecked")
  public static Comparator<AgendaItem> parse(String name) {
    for (Field f : AgendaComparators.class.getDeclaredFields()) {
      if (name.equals(f.getName())) {
        try {
          return (Comparator<AgendaItem>) f.get(null);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }
    return null;
  }

  public static final Comparator<AgendaItem> BY_RELATION = new Comparator<AgendaItem>() {
    private int rank(String relation) {
      switch (relation) {
      case "predicate2":
        return 0;
      case "argument4":
        return 1;
      default:
        throw new RuntimeException("unknown: " + relation);
      }
    }
    @Override
    public int compare(AgendaItem o1, AgendaItem o2) {
      int r1 = rank(o1.edge.getRelation().getName());
      int r2 = rank(o2.edge.getRelation().getName());
      return r1 - r2;
    }
  };

  public static final Comparator<AgendaItem> BY_TARGET = new Comparator<AgendaItem>() {
    @Override
    public int compare(AgendaItem o1, AgendaItem o2) {
      Span t1 = EdgeUtils.target(o1.edge);
      Span t2 = EdgeUtils.target(o2.edge);
      assert t1 != null && t2 != null;
      return Span.BY_END_LR_THEN_WIDTH_THIN.compare(t1, t2);
    }
  };

  public static final Comparator<AgendaItem> BY_FRAME = new Comparator<AgendaItem>() {
    @Override
    public int compare(AgendaItem o1, AgendaItem o2) {
      String f1 = EdgeUtils.frame(o1.edge);
      String f2 = EdgeUtils.frame(o2.edge);
      assert f1 != null && f2 != null;
      return f1.compareTo(f2);
    }
  };

  public static final Comparator<AgendaItem> BY_ROLE = new Comparator<AgendaItem>() {
    @Override
    public int compare(AgendaItem o1, AgendaItem o2) {
      String r1 = EdgeUtils.role(o1.edge);
      String r2 = EdgeUtils.role(o2.edge);
      assert r1 != null && r2 != null;
      return r1.compareTo(r2);
    }
  };

  public static final Comparator<AgendaItem> BY_SCORE = new Comparator<AgendaItem>() {
    @Override
    public int compare(AgendaItem o1, AgendaItem o2) {
      double s1 = o1.score.forwards();
      double s2 = o2.score.forwards();
      assert Double.isFinite(s1) && !Double.isNaN(s1);
      assert Double.isFinite(s2) && !Double.isNaN(s2);
      if (s1 > s2)
        return -1;
      if (s1 < s2)
        return +1;
      return 0;
    }
  };

  public static final Comparator<AgendaItem> BY_ARG = new Comparator<AgendaItem>() {
    @Override
    public int compare(AgendaItem o1, AgendaItem o2) {
      Span s1 = EdgeUtils.arg(o1.edge);
      Span s2 = EdgeUtils.arg(o2.edge);
      assert s1 != null && s2 != null;
      return Span.BY_END_LR_THEN_WIDTH_THIN.compare(s1, s2);
    }
  };

}
