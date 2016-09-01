package edu.jhu.hlt.ikbp;

import edu.jhu.hlt.ikbp.data.Query;
import edu.jhu.hlt.ikbp.data.Response;

public interface Annotator {
  
  public Query nextQuery();

  /**
   * Returned Response should have the same id as the corresponding argument,
   * but where the score now reflects whether this was a good response.
   *
   * Can have many implementations like
   * 1) if r has one extra document, is this response a relevant document to the query
   * 2) if r has one Edge(subject,?), is this a true coreference
   * 3) if r has many Edges, what is their average coref score?
   */
  public Response annotate(Query q, Response r);
}
