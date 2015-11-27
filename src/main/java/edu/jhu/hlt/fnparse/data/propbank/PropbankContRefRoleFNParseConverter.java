package edu.jhu.hlt.fnparse.data.propbank;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.tutils.Span;

/**
 * Converts {@link FNParse}s to {@link FNParse}s. Specifically, takes FNParses
 * where continuation/reference roles are only represented in
 * {@link FrameInstance#getContinuationRoleSpans(int)} and
 * {@link FrameInstance#getReferenceRoleSpans(int)} and turn them into regular
 * FrameInsances where you only need to look at arguments[0..K-1]
 *
 * The reason for having this is that my evaluation code does not know about the
 * CR fields and it is easier to do this transform than update that evaluation
 * code.
 * 
 * The mapping used is:
 * k   -> k
 * C-k -> k + K
 * R-k -> k + 2*K
 *
 * So this will break the invariant that arguments.length == frame.numRoles(),
 * in fact arguments.length == 3 * frame.numRoles().
 *
 * @author travis
 */
public class PropbankContRefRoleFNParseConverter {

  public static FNParse flatten(FNParse y) {
    Sentence sent = y.getSentence();
    List<FrameInstance> fis = new ArrayList<>();
    for (FrameInstance fi : y.getFrameInstances()) {
      int K = fi.getFrame().numRoles();

      // Map the arguments over
      Span[] args = new Span[K * 3];
      Arrays.fill(args, Span.nullSpan);
      for (int k = 0; k < K; k++) {
        Span s = fi.getArgument(k);
        args[k] = s;
        List<Span> cont = fi.getContinuationRoleSpans(k);
        List<Span> ref = fi.getReferenceRoleSpans(k);
        if (cont.size() > 1) throw new RuntimeException();
        if (ref.size() > 1) throw new RuntimeException();
        if (cont.size() > 0)
          args[k + K] = cont.get(0);
        if (ref.size() > 0)
          args[k + 2*K] = ref.get(0);
      }

      // Map the Frame over into a special dummy frame
      Frame f = fi.getFrame();
      String[] roles = new String[K * 3];
      for (int k = 0; k < K; k++) {
        roles[k] = f.getRole(k);
        roles[k + K] = "C-" + f.getRole(k);
        roles[k + 2*K] = "R-" + f.getRole(k);
      }
      Frame ff = new Frame(f.getId(), f.getName() + "-flat", null, roles);

      fis.add(FrameInstance.newFrameInstance(ff, fi.getTarget(), args, sent));
    }
    return new FNParse(sent, fis);
  }

}
