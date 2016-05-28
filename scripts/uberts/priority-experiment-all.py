
# 17 satisfying assignments
def weights():
  w = [0, 3, 4, 6, 12]  # sum to 12
  for bfs in w:
    for dfs in w:
      if bfs * dfs > 0:
        continue
      for easyfirst in w:
        for leftright in w:
          if bfs + dfs + easyfirst + leftright == 12:
            yield {'bfs':bfs, 'dfs':dfs, 'easyfirst':easyfirst, 'leftright':leftright}

def wformat(d):
  s = ''
  for priority, weight in d.iteritems():
    if weight == 0:
      continue
    if len(s) > 0:
      s += ' + '
    s += "%d * %s" % (weight, priority)
  return s

if __name__ == '__main__':
  import os, sys, subprocess
  if len(sys.argv) != 2:
    print 'please provide 1) a working directory and 2) a JAR file (to be copied)'
    sys.exit(1)

  wd = sys.argv[1]
  jar = sys.argv[2]

  if not os.path.isdir(wd):
    print 'working directory doesn\'t exist:', wd
    sys.exit(1)
  if not os.path.isfile(jar):
    print 'JAR is not file:', jar
    sys.exit(1)
  
  p = os.path.join(wd, 'priority-experiments')
  print 'copying jar to safe place:', p
  os.makedirs(p)
  jar_stable = os.path.join(p, 'uberts.jar')
  shutil.copy(jar, jar_stable)

  d = os.path.dirname(os.path.abspath(__file__))
  sc = os.path.join(d, 'priority-experiment.sh')
  for i, w in enumerate(weights()):
    cmd = ['qsub', sc, wd, wformat(w)]
    print i, cmd
    subprocess.check_call(cmd)

