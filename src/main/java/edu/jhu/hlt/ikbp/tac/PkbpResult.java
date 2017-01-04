package edu.jhu.hlt.ikbp.tac;

import java.util.List;

/** n-entities => list of situations containing these entities */
public class PkbpResult {
  double salience;    // for ranking PkbEntries to show to the user based on their query
  List<PkbpEntity> args;   // for now: length=2 and arg0 is always the seed entity
  List<PkbpSituation> situations;    // should this be mentions instead of situations?
}