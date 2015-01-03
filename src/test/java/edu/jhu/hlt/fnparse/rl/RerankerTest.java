package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;

public class RerankerTest {

  private Reranker r = new Reranker();
  private ItemProvider ip = Reranker.getItemProvider();

  @Test
  public void test0() {
    r.train(ip);
    List<FNParse> y = new ArrayList<>();
    List<FNParse> hyp = new ArrayList<>();
    for (int i = 0; i < ip.size(); i++) {
      y.add(ip.label(i));
      hyp.add(r.predict(DataUtil.convertParseToTagging(ip.label(i))));
    }
    BasicEvaluation.showResults("[test0]", BasicEvaluation.evaluate(y, hyp));
  }
}
