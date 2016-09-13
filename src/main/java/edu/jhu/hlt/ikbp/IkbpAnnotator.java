package edu.jhu.hlt.ikbp;

import edu.jhu.hlt.ikbp.data.Query;
import edu.jhu.hlt.ikbp.data.Response;

/**
 * An annotator provides queries and feedback on their quality. Implementations
 * include a human in the loop doing IKBP and dataset-specific annotators which
 * serve up queries and responses solely for the purpose of training and evaluating models.
 *
 * {@link IkbpAnnotator}  {@link IkbpSearch}
 *  0: Query ->
 *  1:                     <- Response+
 *  2: Response* ->                        score field is used for label, kb_delta can be omitted/empty
 *  3: goto 0 or exit
 *
 * @author travis
 */
public interface IkbpAnnotator {
  
  public Query nextQuery();

  /**
   * Returned Response should have the same id as the corresponding argument,
   * but where the score now reflects whether the nodes and edges in the
   * {@link Response}'s delta are good.
   *
   * Can have many implementations like
   * 1) if r has one extra document, is this response a relevant document to the query
   * 2) if r has one Edge(subject,?), is this a true coreference
   * 3) if r has many Edges, what is their average coref score?
   */
  public Response annotate(Query q, Response r);
}
