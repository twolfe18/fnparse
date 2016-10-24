package edu.jhu.hlt.ikbp.evaluation;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.ikbp.data.Query;
import edu.jhu.hlt.ikbp.data.Response;

/**
 * All of the data produced by doing one query, response, and annotation cycle.
 */
public class QueryResponseAnnotations {

  private Query query;
  private List<Response> responses;    // score is model score in [0,1]
  private List<Response> labels;       // score is relevance assessment in [0,1]
  
  public QueryResponseAnnotations(Query q) {
    this.query = q;
    this.responses = new ArrayList<>();
    this.labels = new ArrayList<>();
  }
  
  public Query getQuery() {
    return query;
  }
  
  public void add(Response response, Response label) {
    assert response.getId().equals(label.getId())
      : "response.id=" + response.getId()
      + " label.id=" + label.getId();
    responses.add(response);
    labels.add(label);
  }
  
  public Response getGold(int i) {
    return labels.get(i);
  }
  
  public Response getPred(int i) {
    return responses.get(i);
  }
  
  public int size() {
    return responses.size();
  }
  
  /**
   * You can call this if every possible link/response/mention
   * will appear somewhere in the ranking.
   * 
   * @return null if there are no relevant responses.
   */
  public Double averagePrecisionAssumingPerfectRecall() {
    double totalRel = 0;
    for (Response y : labels) {
      assert y.getScore() >= 0 && y.getScore() <= 1;
      totalRel += y.getScore();
    }
    if (totalRel == 0)
      return null;
    return averagePrecision(totalRel);
  }

  public double averagePrecision(double numRelevant) {
    double p = 0;
    double pZ = 0;
    double ap = 0;
    double partialRel = 0;
    for (int i = 0; i < labels.size(); i++) {
      Response y = labels.get(i);
      assert y.getScore() >= 0 && y.getScore() <= 1;
      p += y.getScore();
      pZ += 1;
      ap += (p / pZ) * y.getScore();
      partialRel += y.getScore();
    }
    assert partialRel <= numRelevant;
    return ap / numRelevant;
  }
  
  public double meanSquaredError() {
    double mse = 0;
    for (int i = 0; i < labels.size(); i++) {
      Response y = labels.get(i);
      Response yhat = responses.get(i);
      double resid = y.getScore() - yhat.getScore();
      mse += resid * resid;
    }
    return Math.sqrt(mse);
  }
}