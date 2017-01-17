package edu.jhu.hlt.ikbp.tac;

import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;

import edu.jhu.hlt.concrete.search.SearchQuery;
import edu.jhu.hlt.concrete.search.SearchResult;
import edu.jhu.hlt.concrete.search.SearchResultItem;
import edu.jhu.hlt.concrete.search.SearchService;
import edu.jhu.hlt.concrete.search.SearchType;
import edu.jhu.hlt.ikbp.tac.TacKbp.KbpQuery;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;

/**
 * Run through some KBP slot-fill queries and send them to a SearchService.
 *
 * @author travis
 */
public class KbpEntitySearchServiceExample {
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    
    String host = config.getString("host");
    int port = config.getInt("port");
    Log.info("connecting to SearchService at host=" + host + " port=" + port);
    
//    for (KbpQuery q : TacKbp.getKbp2013SfQueries()) {
    for (KbpQuery q : TacKbp.getKbpSfQueries("sf13+sf14")) {
      // Talk to a SearchService
      TTransport transport = new TFramedTransport(new TSocket(host, port));
      transport.open();
      TProtocol prot = new TCompactProtocol(transport);
      SearchService.Client client = new SearchService.Client(prot);
      //Log.info("about: " + client.about());

      Log.info("query: " + q);

      SearchQuery query = new SearchQuery();
      query.setCommunicationId(q.docid);
      query.setType(SearchType.SENTENCES);
      query.setName(q.name);
      SearchResult res = null;
      try {
        res = client.search(query);
      } catch (Exception e) {
        //e.printStackTrace();
        System.out.println("no results b/c: " + e.getMessage());
        continue;
      }

      Log.info("got " + res.getSearchResultItemsSize() + " results back");
      for (SearchResultItem r : res.getSearchResultItems()) {
        System.out.println(r);
      }
      System.out.println();

      transport.close();
    }
  }

}
