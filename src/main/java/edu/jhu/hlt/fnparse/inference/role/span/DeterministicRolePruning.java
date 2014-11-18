package edu.jhu.hlt.fnparse.inference.role.span;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.stages.Stage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.fnparse.util.DependencyBasedXuePalmerRolePruning;
import edu.jhu.hlt.fnparse.util.Describe;

public class DeterministicRolePruning
    implements Stage<FNTagging, FNParseSpanPruning> {
  private static final long serialVersionUID = 1L;
  public static final Logger LOG =
      Logger.getLogger(DeterministicRolePruning.class);

  public static enum Mode {
    // Take all constituents in Stanford's constituency parse
    // (regardless of frame/role)
    STANFORD_CONSTITUENTS,

    // Take all spans created by projecting a dependency tree onto spans.
    // (regardless of frame/role)
    DEPENDENCY_SPANS,

    // Constituency tree version is described in the section
    // "Pruning Algorithm" in
    // http://www.cs.brandeis.edu/~xuen/publications/emnlp04.pdf
    // Uses the Stanford constituency parser.
    XUE_PALMER,

    // Like XUE_PALMER_DEP_HERMAN, but on constituency trees
    XUE_PALMER_HERMANN,

    // Based on XUE_PALMER, but first converting dep tree to constituent tree
    XUE_PALMER_DEP,

    // Described in the section "Argument Candidates" in
    // http://www.dipanjandas.com/files/acl2014frames.pdf
    // NOTE: under projectivity + the assumption that all argument spans are
    // contiguous, this will have 100% recall of argument spans produced by
    // Algorithm 2 in Johansson and Nuges (2008)
    // http://www.aclweb.org/anthology/D08-1008
    XUE_PALMER_DEP_HERMANN,
  }

  private Mode mode = Mode.STANFORD_CONSTITUENTS;
  private final FgModel weights = new FgModel(0);
  private ConcreteStanfordWrapper parser =
      ConcreteStanfordWrapper.getSingleton(true);

  public DeterministicRolePruning(Mode mode) {
    this.mode = mode;
  }

  public void configure(java.util.Map<String,String> configuration) {
    String key = "deterministicRolePruningMethod";
    String value = configuration.get(key);
    if (value != null) {
      LOG.info("setting " + key + " to " + value);
      mode = Mode.valueOf(value);
      assert mode != null;
    }
  }

  @Override
  public FgModel getWeights() {
    return weights;
  }

  @Override
  public void setWeights(FgModel weights) {
    LOG.info("[setWeights] not actually doing anything");
  }

  @Override
  public boolean logDomain() {
    return false;
  }

  @Override
  public String getName() {
    return this.getClass().getName();
  }

  @Override
  public void scanFeatures(
      List<? extends FNTagging> unlabeledExamples,
      List<? extends FNParseSpanPruning> labels,
      double maxTimeInMinutes,
      int maxFeaturesAdded) {
    LOG.info("[scanFeatures] not actually doing anything");
  }

  @Override
  public void train(List<FNTagging> x, List<FNParseSpanPruning> y) {
    LOG.info("[train] not actually doing anything");
  }

  @Override
  public void scanFeatures(List<FNParse> data) {
    LOG.info("[scanFeatures] not actually doing anything");
  }

  @Override
  public StageDatumExampleList<FNTagging, FNParseSpanPruning> setupInference(
      List<? extends FNTagging> input,
      List<? extends FNParseSpanPruning> output) {
    List<StageDatum<FNTagging, FNParseSpanPruning>> data = new ArrayList<>();
    for (int i = 0; i < input.size(); i++)
      data.add(new SD(input.get(i), mode, parser));
    return new StageDatumExampleList<>(data);
  }

  static class SD implements StageDatum<FNTagging, FNParseSpanPruning> {
    private FNTagging input;
    private Mode mode;
    private ConcreteStanfordWrapper parser;
    public SD(FNTagging input, Mode mode, ConcreteStanfordWrapper parser) {
      this.input = input;
      this.mode = mode;
      this.parser = parser;
    }
    @Override
    public FNTagging getInput() {
      return input;
    }
    @Override
    public boolean hasGold() {
      return false;
    }
    @Override
    public FNParseSpanPruning getGold() {
      throw new RuntimeException();
    }
    @Override
    public LabeledFgExample getExample() {
      FactorGraph fg = new FactorGraph();
      VarConfig gold = new VarConfig();
      return new LabeledFgExample(fg, gold);
    }
    @Override
    public IDecodable<FNParseSpanPruning> getDecodable() {
      return new Decodable(input, mode, parser);
    }
  }

  static class Decodable implements IDecodable<FNParseSpanPruning> {
    private ConcreteStanfordWrapper parser;
    private Mode mode;
    private FNTagging input;
    private FNParseSpanPruning output;
    public Decodable(
        FNTagging input, Mode mode, ConcreteStanfordWrapper parser) {
      this.input = input;
      this.mode = mode;
      this.parser = parser;
    }
    @Override
    public FNParseSpanPruning decode() {
      if (output == null) {
        Map<FrameInstance, List<Span>> possibleSpans = new HashMap<>();
        if (mode == Mode.STANFORD_CONSTITUENTS) {
          Map<Span, String> cons =
              parser.parseSpans(input.getSentence());
          List<Span> consSpans = new ArrayList<>();
          consSpans.addAll(cons.keySet());
          consSpans.add(Span.nullSpan);
          for (FrameInstance fi : input.getFrameInstances()) {
            FrameInstance key = FrameInstance.frameMention(
                fi.getFrame(), fi.getTarget(), fi.getSentence());
            List<Span> old = possibleSpans.put(key, consSpans);
            assert old == null;
          }
        } else if (mode == Mode.XUE_PALMER
            || mode == Mode.XUE_PALMER_HERMANN) {
          ConstituencyParse parse = new ConstituencyParse(
              parser.parse(input.getSentence(), false));
          for (FrameInstance fi : input.getFrameInstances()) {
            ConstituencyParse.Node pred =
                parse.getConstituent(fi.getTarget());
            Set<Span> spanSet = new HashSet<>();
            if (pred == null) {
              LOG.warn("[XUE_PALMER decode] target is not a span! "
                  + Describe.span(fi.getTarget(), fi.getSentence()));
            } else {
              xuePalmerHelper(pred, spanSet);
            }
            if (mode == Mode.XUE_PALMER_HERMANN && pred != null) {
              Span parent;
              if (pred.getParent() == null) {
                parent = Span.getSpan(0, fi.getSentence().size());
                LOG.warn("pred has no parent " + pred + " in "
                    + fi.getSentence().getId());
              } else {
                parent = pred.getParent().getSpan();
              }
              int s, e;
              // 1)
              spanSet.add(fi.getTarget());
              // 2)
              s = fi.getTarget().end;
              e = parent.end;
              if (s < e)
                spanSet.add(Span.getSpan(s, e));
              // 3)
              s = parent.start;
              e = fi.getTarget().start;
              if (s < e)
                spanSet.add(Span.getSpan(s, e));
            }
            List<Span> spans = new ArrayList<>();
            spans.add(Span.nullSpan);
            spans.addAll(spanSet);
            FrameInstance key = FrameInstance.frameMention(
                fi.getFrame(), fi.getTarget(), fi.getSentence());
            possibleSpans.put(key, spans);
          }
        } else if (mode == Mode.XUE_PALMER_DEP
            || mode == Mode.XUE_PALMER_DEP_HERMANN) {
          parser.parse(input.getSentence(), true);
          possibleSpans = DependencyBasedXuePalmerRolePruning
              .getMask(input, mode);
        } else if (mode == Mode.DEPENDENCY_SPANS) {
          Map<Span, Integer> spanMap =
              DependencyBasedXuePalmerRolePruning
              .getAllSpansFromDeps(input.getSentence(), true);
          List<Span> spans = new ArrayList<>();
          spans.add(Span.nullSpan);
          spans.addAll(spanMap.keySet());
          for (FrameInstance fi : input.getFrameInstances()) {
            FrameInstance key = FrameInstance.frameMention(
                fi.getFrame(), fi.getTarget(), fi.getSentence());
            possibleSpans.put(key, spans);
          }
        } else {
          throw new RuntimeException("unknown mode: " + mode);
        }
        output = new FNParseSpanPruning(
            input.getSentence(),
            input.getFrameInstances(),
            possibleSpans);
      }
      return output;
    }
  }

  private static void xuePalmerHelper(
      ConstituencyParse.Node node,
      Collection<Span> spans) {
    spans.add(node.getSpan());
    for (ConstituencyParse.Node sib : node.getSiblings()) {
      spans.add(sib.getSpan());
      if ("PP".equals(sib.getTag())) {
        for (ConstituencyParse.Node niece : sib.getChildren())
          spans.add(niece.getSpan());
      }
    }
    if (node.getParent() != null)
      xuePalmerHelper(node.getParent(), spans);
  }

  @Override
  public void saveModel(File file) {
    LOG.info("not actually saving anything");
  }

  @Override
  public void loadModel(File file) {
    LOG.info("not actually loading anything");
  }

  public static void main(String[] args) {
    DeterministicRolePruning prune =
        new DeterministicRolePruning(Mode.XUE_PALMER_DEP_HERMANN);
    for (FNParse parse : DataUtil.iter2list(
        FileFrameInstanceProvider.debugFIP.getParsedSentences())) {
      FNParseSpanPruning mask = prune.setupInference(
          Arrays.asList(parse), null).decodeAll().get(0);
      LOG.info(Describe.fnParse(parse));
      for (int i = 0; i < mask.numFrameInstances(); i++) {
        FrameInstance frame = mask.getFrameInstance(i);
        LOG.info("sentence:\n" + Describe.sentenceWithDeps(mask.getSentence(), true));
        LOG.info("possible args for " + Describe.frameInstance(frame));
        for (Span s : mask.getPossibleArgs(i))
          LOG.info("\t" + Describe.span(s, parse.getSentence()));
        LOG.info("");
      }
      LOG.info("------------------------------------------------------");
    }
  }
}

