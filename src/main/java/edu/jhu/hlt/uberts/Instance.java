package edu.jhu.hlt.uberts;

/**
 * @deprecated see goldEdges in {@link Uberts}.
 *
 * @author travis
 */
public interface Instance {

  /**
   * Add any presumed {@link HypEdge}s before starting inference.
   */
  void setupState(Uberts u);

  /**
   * +1 means a gold edge, good thing to add. -1 means a bad edge which
   * introduces loss. You can play with anything in between.
   *
   * TODO This needs to be expanded upon. There are lots of small issues like
   * you can just add this into the oracle, you have to know when something is
   * perfectly correct vs not... etc.
   */
  double label(HypEdge e);

}
