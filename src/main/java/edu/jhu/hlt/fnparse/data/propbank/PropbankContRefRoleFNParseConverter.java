package edu.jhu.hlt.fnparse.data.propbank;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;

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
 * k   -> 3 * k + 0
 * C-k -> 3 * k + 1
 * R-k -> 3 * k + 2
 *
 * So this will break the invariant that arguments.length == frame.numRoles(),
 * in fact arguments.length == 3 * frame.numRoles().
 *
 * @author travis
 */
public class PropbankContRefRoleFNParseConverter {

  // TODO

}
