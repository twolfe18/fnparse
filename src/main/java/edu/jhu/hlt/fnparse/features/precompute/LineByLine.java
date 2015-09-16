package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;

import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;

public interface LineByLine {

  public void observeLine(String line);

  default public void run(File features) throws IOException {
    TimeMarker tm = new TimeMarker();
    try (BufferedReader r = FileUtil.getReader(features)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        observeLine(line);
        if (tm.enoughTimePassed(15)) {
          Log.info("processed " + tm.numMarks()
              + " lines in " + tm.secondsSinceFirstMark() + " seconds, "
              + Describe.memoryUsage());
        }
      }
    }
  }

}
