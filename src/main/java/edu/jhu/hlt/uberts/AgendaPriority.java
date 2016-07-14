package edu.jhu.hlt.uberts;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import edu.jhu.hlt.fnparse.util.LearningRateSchedule.Exp;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;
import edu.jhu.prim.tuple.Pair;

public interface AgendaPriority extends BiFunction<HypEdge, Adjoints, Double> {
  public static int DEBUG = 1;

  public double priority(HypEdge edge, Adjoints score);

  @Override
  default public Double apply(HypEdge e, Adjoints s) {
    return priority(e, s);
  }

  public static AgendaPriority parse(String description) {
    if (DEBUG > 0)
      Log.info("parsing agenda priority " + description);
    String[] toks = description.split("\\+");
    WeightedSum w = new WeightedSum();
    for (int i = 0; i < toks.length; i++) {
      String[] wp = toks[i].trim().split("\\*");
      if (wp.length == 1) {
        assert toks.length == 1 : "assuming weight=1 only works if there are no other terms! " + description;
        AgendaPriority p = byName(wp[0].trim());
        w.add(p, 1);
      } else {
        assert wp.length == 2;
        double weight = Double.parseDouble(wp[0].trim());
        AgendaPriority p = byName(wp[1].trim());
        w.add(p, weight);
      }
    }
    return w;
  }

  public static AgendaPriority byName(String name) {
    switch (name.toLowerCase()) {
    case "leftright":
    case "left2right":
    case "left-right":
    case "l2r":
      return new LeftRight();
    case "easyfirst":
    case "easy-first":
    case "bestfirst":
    case "best-first":
      return new EasyFirst();
    case "dfs":
      return new Dfs();
    case "bfs":
      return new Bfs();
    case "arg4target":
      return new Arg4Target();
    case "frequency":
      ExperimentProperties config = ExperimentProperties.getInstance();
      List<File> train = config.getExistingFiles("train.facts");
      return new Arg4ByRoleFrequency(train.get(0));
    default:
      throw new IllegalArgumentException("don't know about: " + name);
    }
  }

  public static class LeftRight implements AgendaPriority {
    public double slope = 1/5d;   // smaller slopes care less about left-right
    @Override
    public double priority(HypEdge edge, Adjoints score) {
      return slope * -last(edge);
    }
    static int last(HypEdge edge) {
      switch (edge.getRelation().getName()) {
      case "event1":
      case "predicate2":
      case "srl3":
        return Span.inverseShortString((String) edge.getTail(0).getValue()).end;
      case "srl2":        // (t,s)
        return Math.max(
            Span.inverseShortString((String) edge.getTail(0).getValue()).end,
            Span.inverseShortString((String) edge.getTail(1).getValue()).end);
      case "argument4":   // (t,f,s,k)
        return Math.max(
            Span.inverseShortString((String) edge.getTail(0).getValue()).end,
            Span.inverseShortString((String) edge.getTail(2).getValue()).end);
      default:
        throw new RuntimeException("don't know about: " + edge);
      }
    }
  }

  public static class EasyFirst implements AgendaPriority {
    // helps avoid saturation, requires knowing typical score range
    public double scale = 5;
    @Override
    public double priority(HypEdge edge, Adjoints score) {
      return Math.tanh(score.forwards() / scale);
    }
  }

  public static class Dfs implements AgendaPriority {
    @Override
    public double priority(HypEdge edge, Adjoints score) {
      switch (edge.getRelation().getName()) {
      case "event1":
        return -2;
      case "predicate2":
        return -1;
      case "srl2":
        return 0;
      case "srl3":
        return +1;
      case "argument4":
        return +2;
      default:
        throw new RuntimeException("don't know about: " + edge);
      }
    }
  }

  public static class Bfs implements AgendaPriority {
    private Dfs dfs = new Dfs();
    @Override
    public double priority(HypEdge edge, Adjoints score) {
      return -dfs.priority(edge, score);
    }
  }

  public static class Arg4Target implements AgendaPriority {
    @Override
    public double priority(HypEdge edge, Adjoints score) {
      if (!edge.getRelation().getName().equals("argument4"))
        return 0;
      Span t = Span.inverseShortString((String) edge.getTail(0).getValue());
      return -t.start;
    }
  }

  public static class Arg4ByRoleFrequency implements AgendaPriority {
    private Counts<String> roleCounts;
    private File providence;

    private static File cacheFor(File containsArgument4Facts) {
      String f = Hash.sha256(containsArgument4Facts.getPath()) + ".jser.gz";
      return new File("/tmp/Arg4ByRoleFrequency-cache-" + f);
    }

    public Arg4ByRoleFrequency(File containsArgument4Facts) {
      ExperimentProperties config = ExperimentProperties.getInstance();
      boolean c = config.getBoolean("cacheArg4RoleFreqCounts", false);
      File cache = cacheFor(containsArgument4Facts);
      if (c && cache.isFile()) {
        providence = cache;
        roleCounts = (Counts) FileUtil.deserialize(cache);
      } else {
        Log.info("reading role counts from " + containsArgument4Facts.getPath());
        roleCounts = new Counts<>();
        providence = containsArgument4Facts;
        TimeMarker tm = new TimeMarker();
        try (RelationFileIterator rels = new RelationFileIterator(containsArgument4Facts, false);
            ManyDocRelationFileIterator many = new ManyDocRelationFileIterator(rels, true)) {
          int docs = 0;
          while (many.hasNext()) {
            docs++;
            RelDoc d = many.next();
            assert d.facts.isEmpty();
            for (RelLine l : d.items) {
              if (l.tokens[1].equals("argument4")) {
                String k = l.tokens[2+3];
                roleCounts.increment(k);
              }
            }

            if (tm.enoughTimePassed(15)) {
              Log.info("totalCount=" + roleCounts.getTotalCount()
              + " docs=" + docs
              + " after " + tm.secondsSinceFirstMark() + " seconds");
            }
          }
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        Log.info("done, counts: " + roleCounts);
        if (c) {
          Log.info("saving cache to " + cache.getPath());
          FileUtil.serialize(roleCounts, cache);
        }
      }
    }
    public File getProvidence() {
      return providence;
    }
    @Override
    public double priority(HypEdge edge, Adjoints score) {
      String r = edge.getRelation().getName();
      if (!r.equals("argument4")) {
        // Not relevant
        return 0;
      }
      String k = (String) edge.getTail(3).getValue();
      int c = roleCounts.getCount(k);
      if (c == 0)
        Log.info("WARNING: unseen role: " + k);
      double p = ((double) c) / roleCounts.getTotalCount();
//      if (Agenda.DEBUG)
//        Log.info("p=" + p + " " + edge);
      return p;
    }
  }

  public static class WeightedSum implements AgendaPriority {
    private List<Pair<AgendaPriority, Double>> priorities;
    public WeightedSum() {
      priorities = new ArrayList<>();
    }
    public void add(AgendaPriority p, double w) {
      this.priorities.add(new Pair<>(p, w));
    }
    public double priority(HypEdge edge, Adjoints score) {
      double p = 0;
      for (Pair<AgendaPriority, Double> pp : priorities)
        p += pp.get2() * pp.get1().priority(edge, score);
      return p;
    }
  }
}
