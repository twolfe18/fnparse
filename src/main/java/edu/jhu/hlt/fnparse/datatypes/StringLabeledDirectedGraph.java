package edu.jhu.hlt.fnparse.datatypes;

import edu.jhu.hlt.concrete.Dependency;
import edu.jhu.hlt.tutils.LabeledDirectedGraph;
import edu.jhu.util.Alphabet;

/**
 * Added last-minute because {@link DependencyParse} only supports trees (every
 * node has a single parent). Just a list of edges.
 *
 * TODO Move to tutils.
 *
 * @author travis
 */
public class StringLabeledDirectedGraph {

  private Alphabet<String> edgeAlph;
  private LabeledDirectedGraph.Builder builder;
  private LabeledDirectedGraph graph;

  public static StringLabeledDirectedGraph fromConcrete(
      edu.jhu.hlt.concrete.DependencyParse p,
      Alphabet<String> edgeAlph,
      int root) {
    if (root < 0)
      throw new IllegalArgumentException();
    StringLabeledDirectedGraph g = new StringLabeledDirectedGraph(edgeAlph);
    for (Dependency e : p.getDependencyList()) {
      int gov = e.isSetGov() && e.getGov() >= 0 ? e.getGov() : root;
      assert e.getDep() != root;
      g.add(gov, e.getDep(), e.getEdgeType());
    }
    g.get();  // builder => graph, equivalent to freezing
    return g;
  }

  public StringLabeledDirectedGraph(Alphabet<String> edgeAlph) {
    this.edgeAlph = edgeAlph;
    builder = new LabeledDirectedGraph().new Builder();
  }

  public void add(int gov, int dep, String label) {
    if (builder == null)
      throw new IllegalStateException();
    int l = edgeAlph.lookupIndex(label, true);
    builder.add(gov, dep, l);
  }

  public LabeledDirectedGraph get() {
    if (graph == null) {
      graph = builder.freeze();
      builder = null;
    }
    return graph;
  }

  public String getEdge(int edgeValue) {
    return edgeAlph.lookupObject(edgeValue);
  }
}
