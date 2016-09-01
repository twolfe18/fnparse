package edu.jhu.hlt.ikbp;

import edu.jhu.hlt.ikbp.data.Query;
import edu.jhu.hlt.ikbp.data.Response;

public interface Search {
  
  Iterable<Response> search(Query q);

}
