package edu.jhu.hlt.uberts;

import java.io.BufferedReader;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.uberts.Agenda.AgendaItem;
import edu.jhu.hlt.uberts.auto.UbertsLearnPipeline;
import edu.jhu.hlt.uberts.srl.EdgeUtils;
import edu.jhu.prim.tuple.Pair;

public class AgendaComparators {

  public static Comparator<AgendaItem> wrapPriority(AgendaPriority p) {
    return new Comparator<AgendaItem>() {
      @Override
      public int compare(AgendaItem o1, AgendaItem o2) {
        double p1 = p.priority(o1.edge, o1.score);
        double p2 = p.priority(o2.edge, o2.score);
        if (p1 > p2)
          return -1;
        if (p1 < p2)
          return +1;
        return 0;
      }
    };
  }

  /**
   * Accepts strings like BY_RELATION, separated by commas.
   * Uses the {@link ExperimentProperties} key "agendaComparator".
   */
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

  public static Comparator<AgendaItem> tryParseWrappedPriority(String name) {
    String prefix = "p2c:";
    if (name.startsWith(prefix)) {
      String suf = name.substring(prefix.length());
      Log.info("suf=" + suf);
      Supplier<Uberts> fu = null;
      AgendaPriority p = AgendaPriority.byName(suf, fu);
      return wrapPriority(p);
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  public static Comparator<AgendaItem> parse(String name) {
    Log.info("name=" + name);

    Comparator<AgendaItem> p2c = tryParseWrappedPriority(name);
    if (p2c != null)
      return p2c;

    for (Field f : AgendaComparators.class.getDeclaredFields()) {
      if (name.equals(f.getName())) {
        try {
          return (Comparator<AgendaItem>) f.get(null);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }
    Log.info("problem, cannot parse: " + name);
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


  public static final Comparator<AgendaItem> BY_ROLE_FREQ = tryParseWrappedPriority("p2c:frequency-role");

  public static final Comparator<AgendaItem> BY_ROLE_EASYFIRST_DYNAMIC = tryParseWrappedPriority("p2c:easyfirst-dynamic");

  /**
   * Sorts roles by the dev-F1 of the local model.
   * This reads in those values which can be created using the
   * output.perfByRoleDir option in {@link UbertsLearnPipeline}.
   *
   * Produce a file for PB and FN (they won't overlap in (f,k) because of f prefixes),
   * cat them together, and provide that last file with the key "easyfirst.static.role".
   */
  public static final Comparator<AgendaItem> BY_ROLE_EASYFIRST_STATIC = new ByScoreOnFile("easyfirst.static.role");

  /**
   * Expects a file with the format:
   * <frame> <role> <score> <rest>?
   * where score must be >= 0
   *
   * The default score for (frame, role) pairs which do not appear in the
   * file is 0.
   */
  private static class ByScoreOnFile implements Comparator<AgendaItem> {
    private Map<Pair<String, String>, Double> fk2score;
    private String configKey;

    public ByScoreOnFile(String configKey) {
      this.configKey = configKey;
      ExperimentProperties config = ExperimentProperties.getInstance();
      File f = config.getFile(configKey, null);
      if (f != null) {
        fk2score = new HashMap<>();
        add(f);
      }
    }

    public void add(File file) {
      Log.info(file.getPath());
      if (!file.isFile())
        throw new IllegalArgumentException();
      try (BufferedReader r = FileUtil.getReader(file)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] toks = line.split("\\s+");
          assert toks.length >= 3;
          String f = toks[0];
          String k = toks[1];
          double score = Double.parseDouble(toks[2]);
          assert score >= 0;
          Object old = fk2score.put(new Pair<>(f, k), score);
          assert old == null;
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    public double getScore(HypEdge e) {
      if (fk2score == null) {
        throw new IllegalStateException("not intialized, "
            + "did you provide file of performance by role with "
            + this.configKey + "?");
      }
      String f = EdgeUtils.frame(e);
      String k = EdgeUtils.role(e);
      Double s = fk2score.get(new Pair<>(f, k));
      if (s == null)
        s = 0d;
      return s;
    }

    @Override
    public int compare(AgendaItem o1, AgendaItem o2) {
      if (o1.edge.getRelation() == o2.edge.getRelation()) {
        assert false : "e1=" + o1.edge + " e2=" + o2.edge;
        return 0;
      }
      double s1 = getScore(o1.edge);
      double s2 = getScore(o2.edge);
      if (s1 > s2)
        return -1;
      if (s1 < s2)
        return +1;
      return 0;
    }
  }

  public static final Comparator<AgendaItem> BY_RAND_STATIC = new Rand();

  // Re-set the seed of this every time inference is run.
  public static final Rand BY_RAND_DYNAMIC = new Rand();

  public static class Rand implements Comparator<AgendaItem> {
    private int seed = 9001;
    public void setSeed(int i) {
      seed = i;
    }
    @Override
    public int compare(AgendaItem o1, AgendaItem o2) {
      long i1 = o1.getHashableEdge().hashCode64();
      long i2 = o2.getHashableEdge().hashCode64();
      i1 = Hash.mix64(seed, i1);
      i2 = Hash.mix64(seed, i2);
      if (i1 < i2)
        return -1;
      if (i1 > i2)
        return +1;
      return 0;
    }
  };
}
