
def global_feats():
  return ['none', 'numArgs', 'argLoc', 'roleCooc', 'full']

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
            yield {'bfs':bfs, 'dfs':dfs, 'easyfirst':max(0.0001, easyfirst), 'leftright':leftright}

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
  import os, sys, subprocess, shutil
  if len(sys.argv) != 4:
    print 'please provide:'
    print '1) a working directory'
    print '2) a feature set directory with <relationName>.fs files'
    print '3) a JAR file (to be copied)'
    sys.exit(1)

  wd = sys.argv[1]
  fs = sys.argv[2]
  jar = sys.argv[3]

  qsub = True

  if not os.path.isdir(wd):
    print 'working directory doesn\'t exist:', wd
    sys.exit(1)
  if not os.path.isfile(jar):
    print 'JAR is not file:', jar
    sys.exit(1)
  
  p = os.path.join(wd, 'priority-experiments')
  print 'copying jar to safe place:', p
  if not os.path.exists(p):
    os.makedirs(p)
  logs = os.path.join(p, 'logs')
  if not os.path.exists(logs):
    os.makedirs(logs)

  jar_stable = os.path.join(p, 'uberts.jar')
  shutil.copy(jar, jar_stable)

  fs_stable = os.path.join(p, 'priority-feature-sets')
  if not os.path.isdir(fs_stable):
    print 'copying features to', fs_stable
    shutil.copytree(fs, fs_stable)

  d = os.path.dirname(os.path.abspath(__file__))
  sc = os.path.join(d, 'priority-experiment.sh')
  i = 0
  for gf in global_feats():
    for w in weights():
      c = 'qsub' if qsub else 'sbatch'
      cmd = [c, '-o', logs, sc, wd, wformat(w), fs_stable, gf, jar_stable]
      print i, cmd
      i += 1
      subprocess.check_call(cmd)
  print 'submitted', i, 'jobs'

