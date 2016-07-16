
import os, sys, subprocess, shutil

def global_feats():
  return ['none', 'numArgs', 'argLoc', 'roleCooc', 'full']

small = 0.001
def weights():
  # #w = [0, 2, 3, 4, 6, 8, 9, 12]  # 55 options which sum to 12
  # #w = [0, 3, 4, 6, 8, 12]  # 27 options which sum to 12
  # #w = [0, 3, 4, 6, 9, 12]  # 27 options which sum to 12
  # w = [0, 3, 4, 6, 12]  # 17 options which sum to 12
  # for bfs in w:
  #   for dfs in w:
  #     if bfs * dfs > 0:
  #       continue
  #     for easyfirst in w:
  #       for leftright in w:
  #         if bfs + dfs + easyfirst + leftright == 12:
  #           yield {'bfs':bfs, 'dfs':dfs, 'easyfirst':max(small, easyfirst), 'leftright':leftright}
  for leftright in [0, 1, 2, 4, 8]:
    yield {'easyfirst':1, 'leftright':leftright}

def wpretty(d):
  terms = [k for k,v in d.iteritems() if v > small]
  terms = sorted(terms, key=lambda k: d[k], reverse=True)
  return '_'.join(k + str(d[k]) for k in terms)

def wformat(d):
  ''' format weights dict for java command line '''
  s = ''
  for priority, weight in d.iteritems():
    if weight == 0:
      continue
    if len(s) > 0:
      s += ' + '
    s += "%f * %s" % (weight, priority)
  return s

def old_main():
  if len(sys.argv) != 5:
    print 'please provide:'
    print '1) an unique experiment name'
    print '2) a working directory'
    print '3) a feature set directory with <relationName>.fs files'
    print '4) a JAR file (to be copied)'
    sys.exit(1)

  name, wd, fs, jar = sys.argv[1:]
  #wd = sys.argv[1]
  #fs = sys.argv[2]
  #jar = sys.argv[3]

  qsub = True

  if not os.path.isdir(wd):
    print 'working directory doesn\'t exist:', wd
    sys.exit(1)
  if not os.path.isfile(jar):
    print 'JAR is not file:', jar
    sys.exit(1)
  
  p = os.path.join(wd, 'priority-experiments-' + name)
  print 'copying jar to safe place:', p
  if not os.path.exists(p):
    os.makedirs(p)
  logs = os.path.join(p, 'logs')
  if not os.path.exists(logs):
    os.makedirs(logs)
  predictions_dir = os.path.join(p, 'predictions')

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

      job_name = gf
      for k,v in w.iteritems():
        job_name += '_' + str(k) + '=' + str(v)

      pd = os.path.join(predictions_dir, job_name)
      if not os.path.exists(pd):
        print 'writing predictions to', pd
        os.makedirs(pd)

      cmd = [c, '-o', logs, sc, \
        wd, pd, wformat(w), fs_stable, gf, jar_stable]

      print i, cmd
      i += 1
      subprocess.check_call(cmd)
  print 'submitted', i, 'jobs'

def bool2str(b):
  assert type(b) == type(True)
  return 'true' if b else 'false'

if __name__ == '__main__':
  if len(sys.argv) != 5:
    print 'please provide:'
    print '1) an unique experiment name'
    print '2) a working directory'
    print '3) a feature set directory with <relationName>.fs files'
    print '4) a JAR file (to be copied)'
    sys.exit(1)

  name, wd, fs, jar = sys.argv[1:]
  #wd = sys.argv[1]
  #fs = sys.argv[2]
  #jar = sys.argv[3]

  mock = True

  engine = 'qsub'

  if not os.path.isdir(wd):
    print 'working directory doesn\'t exist:', wd
    if not mock:
      sys.exit(1)
  if not os.path.isfile(jar):
    print 'JAR is not file:', jar
    if not mock:
      sys.exit(1)
  
  p = os.path.join(wd, 'priority-experiments-' + name)
  if not os.path.exists(p) and not mock:
    os.makedirs(p)

  logs = os.path.join(p, 'logs')
  if not os.path.exists(logs) and not mock:
    os.makedirs(logs)

  model_dir = os.path.join(p, 'models')
  if not os.path.exists(model_dir) and not mock:
    os.makedirs(model_dir)

  predictions_dir = os.path.join(p, 'predictions')
  if not os.path.exists(predictions_dir) and not mock:
    os.makedirs(predictions_dir)

  jar_stable = os.path.join(p, 'uberts.jar')
  print 'copying jar to safe place:'
  print '    ', jar
  print '===>', jar_stable
  if not mock:
    shutil.copy(jar, jar_stable)

  fs_stable = os.path.join(p, 'priority-feature-sets')
  if not os.path.isdir(fs_stable) and not mock:
    print 'copying features to', fs_stable
    shutil.copytree(fs, fs_stable)

  d = os.path.dirname(os.path.abspath(__file__))

  sh_arg = os.path.join(d, 'priority-experiment-argId.sh')
  sh_pred = os.path.join(d, 'priority-experiment-predId.sh')
  sh_omni = os.path.join(d, 'priority-experiment-omni.sh')

  # predId model with L2R and global in [true, false]
  for l2r in [True, False]:
    for gl_frames in [True, False]:
      if gl_frames and l2r:
        continue

      name = 'predicate2'
      name += '.l2r' if l2r else '.bf'
      name += '.global' if gl_frames else '.local'

      gf_str = 'preicate2/t+frameCooc' if gl_frames else ''

      grammar = os.path.join(wd, 'grammar.predicate2.trans')

      param_io = 'predicate2+learn+write:' \
        + os.path.join(model_dir, name + '.jser.gz')

      agenda_priority = '1*easyfirst + 1000*leftright' if l2r else 'easyfirst'
      oracle_relations = 'event1'

      args = [engine, '-cwd', '-o', logs, sh_omni, \
         wd, \
         grammar, \
         os.path.join(predictions_dir, name), \
         agenda_priority,
         oracle_relations,
         param_io,
         fs_stable,
         gf_str,
         jar_stable]

      print args
      if not mock:
        subprocess.check_call(args)

  # argId model (gold predicate2) with agendaPriority x globalFeats
  # TODO argId model (auto predicate2) with agendaPriority x globalFeats
  for gf in global_feats():
    for agenda_priority in weights():

      name = 'argment4'
      #name += '.autoPred' if auto_pred else '.goldPred'
      name += '.goldPred'
      name += '.' + gf
      name += '.' + wpretty(agenda_priority)

      grammar = os.path.join(wd, 'grammar.argument4.trans')

      param_io = 'argument4+learn+write:' \
        + os.path.join(model_dir, name + '.jser.gz')

      oracle_relations = 'event1,predicate2'

      gf_str = 'argument4/t+' + gf

      args = [engine, '-cwd', '-o', logs, sh_omni, \
         wd, \
         grammar, \
         os.path.join(predictions_dir, name), \
         wformat(agenda_priority),
         oracle_relations,
         param_io,
         fs_stable,
         gf_str,
         jar_stable]

      print args
      if not mock:
        subprocess.check_call(args)

