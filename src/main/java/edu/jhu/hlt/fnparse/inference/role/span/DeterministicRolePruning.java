package edu.jhu.hlt.fnparse.inference.role.span;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import org.apache.log4j.Logger;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.FrameRoleInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.stages.Stage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.fnparse.util.DependencyBasedXuePalmerRolePruning;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.GlobalParameters;
import edu.jhu.hlt.fnparse.util.RandomBracketing;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.tutils.Log;

public class DeterministicRolePruning
    implements Stage<FNTagging, FNParseSpanPruning> {
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

    // Bottom up random merges (baseline)
    RANDOM,
  }

  public static boolean PEDANTIC = false;

  private Mode mode = LatentConstituencyPipelinedParser.DEFAULT_PRUNING_METHOD;
  private final FgModel weights = new FgModel(0);
  //private ConcreteStanfordWrapper parser;
  private Function<Sentence, ConstituencyParse> cParser;
  private Function<Sentence, DependencyParse> dParser;

  //public DeterministicRolePruning(Mode mode, ConcreteStanfordWrapper parser) {
  public DeterministicRolePruning(
      Mode mode,
      Function<Sentence, DependencyParse> dParser,
      Function<Sentence, ConstituencyParse> cParser) {
    if (PEDANTIC) {
      if (Arrays.asList(Mode.DEPENDENCY_SPANS, Mode.XUE_PALMER_DEP, Mode.XUE_PALMER_DEP_HERMANN).contains(mode) && dParser == null)
        Log.warn("no dParser provided, all sentences must have dParses!");
      if (Arrays.asList(Mode.STANFORD_CONSTITUENTS, Mode.XUE_PALMER, Mode.XUE_PALMER_HERMANN).contains(mode) && cParser == null)
        Log.warn("no cParser provided, all sentences must have cParses!");
    }
    this.mode = mode;
    this.dParser = dParser;
    this.cParser = cParser;
  }

  public Mode getMode() {
    return mode;
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
    LOG.info("[setupInference] for " + input.size() + " sentences in "
      + mode + " mode");
    List<StageDatum<FNTagging, FNParseSpanPruning>> data = new ArrayList<>();
    for (int i = 0; i < input.size(); i++)
      data.add(this.new SD(input.get(i)));
    return new StageDatumExampleList<>(data);
  }

  class SD implements StageDatum<FNTagging, FNParseSpanPruning> {
    private FNTagging input;
    public SD(FNTagging input) {
      this.input = input;
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
      //return new Decodable(input, mode, parser);
      return DeterministicRolePruning.this.new Decodable(input);
    }
  }

  public class Decodable implements IDecodable<FNParseSpanPruning> {
    //private ConcreteStanfordWrapper parser;
    private FNTagging input;
    private FNParseSpanPruning output;

    /** Doesn't do any caching around parser */
    //public Decodable(FNTagging input, Mode mode, ConcreteStanfordWrapper parser) {
    public Decodable(FNTagging input) {
      this.input = input;
    }

    public ConstituencyParse getConstituencyParse() {
      Sentence s = input.getSentence();
      ConstituencyParse p = s.getStanfordParse(false);
      if (p != null)
        return p;
      if (cParser == null) {
        throw new RuntimeException("Sentence did not contain a parse, "
            + "and you didn't provide a parser. Sentence.id=" + s.getId());
      }
      //return parser.getCParse(s);
      return cParser.apply(s);
    }

    @Override
    public FNParseSpanPruning decode() {
      if (output == null) {
        Sentence sent = input.getSentence();
        Map<FrameInstance, List<Span>> possibleSpans = new HashMap<>();
        if (mode == Mode.RANDOM) {
          Set<Span> cons = new HashSet<>();
          RandomBracketing brack = new RandomBracketing(new Random(9001));
          brack.bracket(input.getSentence().size(), cons);
          List<Span> consSpans = new ArrayList<>();
          consSpans.addAll(cons);
          consSpans.add(Span.nullSpan);
          for (FrameInstance fi : input.getFrameInstances()) {
            FrameInstance key = FrameInstance.frameMention(
                fi.getFrame(), fi.getTarget(), fi.getSentence());
            List<Span> old = possibleSpans.put(key, consSpans);
            assert old == null;
          }
        } else if (mode == Mode.STANFORD_CONSTITUENTS) {
          Set<Span> cons = new HashSet<>();
          ConstituencyParse cp = getConstituencyParse();
          cp.getSpans(cons);
          List<Span> consSpans = new ArrayList<>();
          consSpans.addAll(cons);
          consSpans.add(Span.nullSpan);
          for (FrameInstance fi : input.getFrameInstances()) {
            FrameInstance key = FrameInstance.frameMention(
                fi.getFrame(), fi.getTarget(), fi.getSentence());
            List<Span> old = possibleSpans.put(key, consSpans);
            assert old == null;
          }
        } else if (mode == Mode.XUE_PALMER
            || mode == Mode.XUE_PALMER_HERMANN) {
          ConstituencyParse parse = getConstituencyParse();
          //parse.checkSpans(sent.size());
          for (FrameInstance fi : input.getFrameInstances()) {
            ConstituencyParse.Node pred =
                parse.getConstituent(fi.getTarget());
            Set<Span> spanSet = new HashSet<>();
            if (pred == null) {
              // TODO: single-token targets are gauranteed to be nodes in the
              // tree, but multi-word targets often won't be.
              // Solution: take the smallest node that dominates the entire target.
              LOG.warn("[" + mode + " decode] target is not a span! "
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
            //spans.addAll(spanSet);
            for (Span s : spanSet) {
              if (s.end > sent.size() || s.start < 0 || s.start >= s.end) {
                LOG.warn("bad span: " + s + " vs " + sent.size());
                continue;
              }
              assert s != Span.nullSpan;
              spans.add(s);
            }
            FrameInstance key = FrameInstance.frameMention(
                fi.getFrame(), fi.getTarget(), fi.getSentence());
            possibleSpans.put(key, spans);
          }
        } else if (mode == Mode.XUE_PALMER_DEP
            || mode == Mode.XUE_PALMER_DEP_HERMANN) {
          if (sent.getBasicDeps() == null)
            sent.setBasicDeps(dParser.apply(sent));
            //sent.setBasicDeps(parser.getBasicDParse(sent));
          possibleSpans = DependencyBasedXuePalmerRolePruning
              .getMask(input, mode);
        } else if (mode == Mode.DEPENDENCY_SPANS) {
          if (sent.getBasicDeps() == null)
            sent.setBasicDeps(dParser.apply(sent));
            //sent.setBasicDeps(parser.getBasicDParse(sent));
//          # basic deps
//          107931   INFO  DeterministicRolePruning - DEPENDENCY_SPANS recall 0.5688228657389997
//          108077   INFO  DeterministicRolePruning - XUE_PALMER_DEP recall 0.1855960887551711
//          108103   INFO  DeterministicRolePruning - XUE_PALMER_DEP_HERMANN recall 0.4672809326814592
//          # collapsed deps
//          108448   INFO  DeterministicRolePruning - DEPENDENCY_SPANS recall 0.43249341857841295
//          108617   INFO  DeterministicRolePruning - XUE_PALMER_DEP recall 0.1855960887551711
//          108682   INFO  DeterministicRolePruning - XUE_PALMER_DEP_HERMANN recall 0.4672809326814592
          DependencyParse deps = sent.getBasicDeps();
          //DependencyParse deps = sent.getCollapsedDeps();
          Map<Span, Integer> spanMap =
              DependencyBasedXuePalmerRolePruning
              .getAllSpansFromDeps(deps);
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
      //LOG.debug(String.format("[decode] possible args for n=%d nFI=%d is %d",
      //    input.getSentence().size(), input.numFrameInstances(), output.numPossibleArgs()));
      if (input instanceof FNParse)
        Log.info("pruning recall: " + output.recall((FNParse) input));
      else
        Log.warn("check this");
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
  public void saveModel(DataOutputStream dos, GlobalParameters globals) {
    LOG.info("not actually saving anything");
  }

  @Override
  public void loadModel(DataInputStream dis, GlobalParameters globals) {
    LOG.info("not actually loading anything");
  }

  // shows spans
  public static void main(String[] args) {
    ConcreteStanfordWrapper csw = ConcreteStanfordWrapper.getSingleton(false);
    Function<Sentence, DependencyParse> dParser = csw::getBasicDParse;
    DeterministicRolePruning prune =
        new DeterministicRolePruning(Mode.XUE_PALMER_DEP_HERMANN, dParser, null);
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

  // computes recall for each method
  public static void mainNew(String[] args) {
    List<FNParse> parses = DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences()).subList(0, 500);
    ConcreteStanfordWrapper parser = ConcreteStanfordWrapper.getSingleton(true);
    Function<Sentence, DependencyParse> dParser = parser::getBasicDParse;
    Function<Sentence, ConstituencyParse> cParser = parser::getCParse;
    for (FNParse p : parses) {
      Sentence s = p.getSentence();
      s.setStanfordParse(parser.getCParse(s));
    }
    for (DeterministicRolePruning.Mode mode : DeterministicRolePruning.Mode.values()) {
      DeterministicRolePruning prune = new DeterministicRolePruning(mode, dParser, cParser);
      FPR fpr = new FPR(false);
      for (FNParse p : parses) {
        DeterministicRolePruning.Decodable dec = prune.new Decodable(p);
            //new DeterministicRolePruning.Decodable(p, mode, dParser, cParser);
        FNParseSpanPruning mask = dec.decode();
        Map<FrameRoleInstance, Set<Span>> spans = mask.getMapRepresentation();
        for (FrameInstance fi : p.getFrameInstances()) {
          Frame f = fi.getFrame();
          for (int k = 0; k < f.numRoles(); k++) {
            Span s = fi.getArgument(k);
            if (s == Span.nullSpan)
              continue;
            FrameRoleInstance key = new FrameRoleInstance(f, fi.getTarget(), k);
            if (spans.get(key).contains(s))
              fpr.accumTP();
            else
              fpr.accumFN();
          }
        }
      }
      if (fpr.getTP() > 0)
        LOG.info(mode + " recall " + fpr.recall());
    }
  }
}

