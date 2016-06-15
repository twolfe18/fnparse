package edu.jhu.hlt.uberts.factor;

import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;

/**
 * First example of this is {@link AtLeast1Local}, which watches the scores of
 * facts going in, and ensure that there is at least one fact which has a score
 * greater than 0. TODO better description.
 *
 * @author travis
 */
public interface PreAgendaAddMapper {

  /**
   * Allows you to change the score of an edge just before it is added to the agenda.
   */
  Adjoints map(HashableHypEdge e, Adjoints a);

  /**
   * Called at the same time as Agenda.clear
   */
  void clear();

}
