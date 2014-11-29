package edu.jhu.hlt.fnparse.experiment.grid;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameArgInstance;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.util.Counts;
import edu.jhu.hlt.fnparse.util.PosPatternGenerator;
import edu.jhu.hlt.fnparse.util.PosPatternGenerator.Mode;
import edu.mit.jwi.item.IWord;

/**
 * We'll do leave-one(FrameInstance)-out coverage.
 * 
 * @author travis
 */
public class FeatureCoverage {
  public static final Logger LOG = Logger.getLogger(FeatureCoverage.class);

  abstract class Feature implements Function<Arg, Set<String>> {
    private int unseenAgg = 0, totalAgg = 0;
    private Counts<String> unseen;
    private Counts<String> total;
    private String name;
    public Feature(String name) {
      this.name = name;
      unseen = new Counts<>();
      total = new Counts<>();
    }
    public String getName() {
      return name;
    }
    public void observe(String f, boolean unseen) {
      totalAgg++;
      total.increment(f);
      if (unseen) {
        this.unseen.increment(f);
        unseenAgg++;
      }
    }
    public void show(double threshold) {
      double rr = ((double) unseenAgg) / totalAgg;
      LOG.info(String.format("%s unseen %.1f%% (%d / %d)",
          name, 100d * rr, unseenAgg, totalAgg));
      /*
      for (String s : unseen.getKeysSorted(true)) {
        int u = unseen.getCount(s);
        int t = total.getCount(s);
        double r = ((double) u) / t;
        if (r < threshold)
          continue;
        LOG.info(String.format("%s:%s unseen %.1f%% (%d / %d)",
            name, s, 100d * r, u, t));
      }
      */
    }
  }
  class FeatureAdapter extends Feature {
    private Function<Arg, Set<String>> func;
    public FeatureAdapter(String name, Function<Arg, Set<String>> f) {
      super(name);
      this.func = f;
    }
    @Override
    public Set<String> apply(Arg t) {
      return func.apply(t);
    }
  }

  class Arg extends FrameArgInstance {
    public Sentence sentence;
    public Arg(Frame f, Span t, int k, Span a, Sentence s) {
      super(f, t, k, a);
      this.sentence = s;
    }
  }

  private Counts<String> observations;
  private List<Feature> feats;
  private List<FrameInstance> instances; 

  public void run() {
    instances = new ArrayList<>();
    for (FNParse p : DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences()))
      instances.addAll(p.getFrameInstances());
    LOG.info("after fulltext train section, " + instances.size() + " instances");
    for (FNTagging p : DataUtil.iter2list(FileFrameInstanceProvider.fn15lexFIP.getParsedOrTaggedSentences()))
      instances.addAll(p.getFrameInstances());
    LOG.info("after lex section, " + instances.size() + " instances");

    addCounters();
    count();

    // Show the coverage
    for (Feature f : feats)
      f.show(0.5d);
  }

  public void addCounters() {
    feats = new ArrayList<>();
    feats.add(new FeatureAdapter("roleLU", fai -> {
      Set<String> feats = new HashSet<>();
      HeadFinder hf = SemaforicHeadFinder.getInstance();
      int h = hf.head(fai.argument, fai.sentence);
      String head = fai.sentence.getLemmaLU(h).getFullString();
      String role = fai.frame.getName() + "." + fai.frame.getRole(fai.role);
      feats.add(role + "_" + head);
      return feats;
    }));
    feats.add(new FeatureAdapter("roleLemma", fai -> {
      Set<String> feats = new HashSet<>();
      HeadFinder hf = SemaforicHeadFinder.getInstance();
      int h = hf.head(fai.argument, fai.sentence);
      String head = fai.sentence.getLemmaLU(h).word;
      String role = fai.frame.getName() + "." + fai.frame.getRole(fai.role);
      feats.add(role + "_" + head);
      return feats;
    }));
    feats.add(new FeatureAdapter("roleWnSS", fai -> {
      Set<String> feats = new HashSet<>();
      HeadFinder hf = SemaforicHeadFinder.getInstance();
      int h = hf.head(fai.argument, fai.sentence);
      IWord word = fai.sentence.getWnWord(h);
      String head = "???";
      if (word != null && word.getSynset() != null)
        head = word.getSynset().getID().toString();
      String role = fai.frame.getName() + "." + fai.frame.getRole(fai.role);
      feats.add(role + "_" + head);
      return feats;
    }));
    feats.add(new FeatureAdapter("rolePosPat", fai -> {
      Set<String> feats = new HashSet<>();
      PosPatternGenerator pgen = new PosPatternGenerator(0, 0, Mode.COARSE_POS);
      String head = pgen.extract(fai.argument, fai.sentence);
      String role = fai.frame.getName() + "." + fai.frame.getRole(fai.role);
      feats.add(role + "_" + head);
      return feats;
    }));
    feats.add(new FeatureAdapter("rolePos", fai -> {
      Set<String> feats = new HashSet<>();
      HeadFinder hf = SemaforicHeadFinder.getInstance();
      int h = hf.head(fai.argument, fai.sentence);
      String head = fai.sentence.getPos(h);
      String role = fai.frame.getName() + "." + fai.frame.getRole(fai.role);
      feats.add(role + "_" + head);
      return feats;
    }));
    feats.add(new FeatureAdapter("roleDir", fai -> {
      Set<String> feats = new HashSet<>();
      String head = fai.argument.after(fai.target)
          ? "after"
          : fai.argument.before(fai.target)
            ? "before"
            : "other";
      String role = fai.frame.getName() + "." + fai.frame.getRole(fai.role);
      feats.add(role + "_" + head);
      return feats;
    }));
    feats.add(new FeatureAdapter("role", fai -> {
      Set<String> feats = new HashSet<>();
      String role = fai.frame.getName() + "." + fai.frame.getRole(fai.role);
      feats.add(role);
      return feats;
    }));
    feats.add(new FeatureAdapter("coarseRole", fai -> {
      Set<String> feats = new HashSet<>();
      String role = fai.frame.getRole(fai.role);
      feats.add(role);
      return feats;
    }));
  }

  public void count() {
    observations = new Counts<>();

    // Put them all in
    for (int i = 0; i < instances.size(); i++)
      for (Feature f : feats)
        update(instances.get(i), f, true);

    // Take them out one by one
    for (int i = 0; i < instances.size(); i++) {
      for (Feature f : feats) {
        Set<String> feats = extract(instances.get(i), f);
        update(instances.get(i), f, false);
        for (String s : feats)
          f.observe(s, !seen(s));
      }
    }
  }

  private boolean seen(String feat) {
    return observations.getCount(feat) > 0;
  }

  private Set<String> extract(FrameInstance fi, Function<Arg, Set<String>> func) {
    Set<String> union = new HashSet<>();
    Sentence s = fi.getSentence();
    Frame f = fi.getFrame();
    Span t = fi.getTarget();
    for (int k = 0; k < f.numRoles(); k++) {
      Span a = fi.getArgument(k);
      if (a == Span.nullSpan)
        continue;
      Arg fai = new Arg(f, t, k, a, s);
      union.addAll(func.apply(fai));
    }
    return union;
  }

  private void update(
      FrameInstance fi,
      Function<Arg, Set<String>> func,
      boolean add) {
    update(extract(fi, func), add);
  }

  private void update(Set<String> feats, boolean add) {
    for (String s : feats)
      observations.update(s, add ? 1 : -1);
  }

  public static void main(String[] args) {
    new FeatureCoverage().run();
  }
}
