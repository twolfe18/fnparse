package edu.jhu.hlt.fnparse.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

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

  /** glob should look like "glob:**\/*" -- without the escape backslash of course */
  default public void runManyFiles(String glob, File parent) throws IOException {
    PathMatcher pm = FileSystems.getDefault().getPathMatcher(glob);
    Files.walkFileTree(parent.toPath(), new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
        if (pm.matches(path)) {
          File f = path.toFile();
          Log.info("reading: " + f.getPath() + "\t" + Describe.memoryUsage());
          LineByLine.this.run(f);
        }
        return FileVisitResult.CONTINUE;
      }
      @Override
      public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
        return FileVisitResult.CONTINUE;
      }
    });
  }
}
