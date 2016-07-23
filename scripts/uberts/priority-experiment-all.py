
import os, sys, subprocess, shutil

def single_global_feature_configs():
  d = {}
  d['+numArgs'] = ['@F', '@F_1']
  d['+argLocGlobal'] = d['+argLocPairwise'] = ['@F', '@F_1', '@FK', '@FK_1', '@K', '@K_F']
  d['+roleCooc'] = ['@1', '@F', '@F_1']
  for gf, refs in d.iteritems():
    for r in refs:
      yield gf + r

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

def weights_new(extra_combinations=False):
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
  lrs = [0, 1]
  a4ts = [0, 1000]
  fs = [1]
  if extra_combinations:
    lrs = [0, 1, 2, 4, 8]
    fs = [0, 1]
  for arg4target in a4ts:
    for frequency in fs:
      for leftright in lrs:
        yield {'easyfirst':1, 'leftright':leftright, 'arg4target':arg4target, 'frequency':frequency}

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

def bool2str(b):
  assert type(b) == type(True)
  return 'true' if b else 'false'

if __name__ == '__main__':
  if len(sys.argv) != 6:
    print 'please provide:'
    print '1) an exeperiment string, comma separated, (pred2|arg4fine|arg4coarse)'
    print '2) an unique experiment name'
    print '3) a working directory'
    print '4) a feature set directory with <relationName>.fs files'
    print '5) a JAR file (to be copied)'
    sys.exit(1)

  exp_names, tag, wd, fs, jar = sys.argv[1:]

  exp_names = set(x.strip().lower() for x in exp_names.split(','))

  mock = False

  engine = 'qsub'

  if not os.path.isdir(wd):
    print 'working directory doesn\'t exist:', wd
    if not mock:
      sys.exit(1)
  if not os.path.isfile(jar):
    print 'JAR is not file:', jar
    if not mock:
      sys.exit(1)
  
  p = os.path.join(wd, 'priority-experiments-' + tag)
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

  sh_omni = os.path.join(d, 'priority-experiment-omni.sh')

  train_method = 'MAX_VIOLATION'

  # predId model with L2R and global in [true, false]
  if 'pred2' not in exp_names:
    print 'skipping pred2'
  else:
    pred2_grammar = os.path.join(wd, 'rel-data/grammar.predicate2.trans')
    if not os.path.isfile(pred2_grammar):
      print 'couldn\'t find pred2_grammar file: ' + pred2_grammar
      if not mock:
        sys.exit(10)
    for l2r in [True, False]:
      for gl_frames in [True, False]:
        if gl_frames and l2r:
          continue

        name = 'predicate2'
        name += '.l2r' if l2r else '.bf'
        name += '.global' if gl_frames else '.local'

        gf_str = 'predicate2/t+frameCooc' if gl_frames else ''

        param_io = 'predicate2+learn+write:' \
          + os.path.join(model_dir, name + '.jser.gz')

        agenda_priority = '1*easyfirst + 1000*leftright' if l2r else 'easyfirst'
        oracle_relations = 'event1'

        job_name = tag + '-' + name
        args = [engine, '-N', job_name, '-cwd', '-o', logs, sh_omni, \
           wd, \
           pred2_grammar, \
           os.path.join(predictions_dir, name), \
           agenda_priority, \
           oracle_relations, \
           param_io, \
           fs_stable, \
           gf_str, \
           train_method, \
           jar_stable]

        print args
        if not mock:
          subprocess.check_call(args)

  # argId model (gold predicate2) with agendaPriority x globalFeats
  # TODO argId model (auto predicate2) with agendaPriority x globalFeats
  arg4_grammar = os.path.join(wd, 'rel-data/grammar.argument4.trans')
  if not os.path.isfile(arg4_grammar):
    print 'couldn\'t find arg4_grammar file: ' + arg4_grammar
    if not mock:
      sys.exit(11)

  ### Test out different types of Fy for various global features
  if 'arg4fine' not in exp_names:
    print 'skipping arg4fine'
  else:
    agenda_comparator = 'BY_RELATION,BY_TARGET,BY_FRAME,BY_ROLE,BY_SCORE'
    for gf_str in single_global_feature_configs():
      name = 'gfRefs.argment4'
      name += '.' + gf_str.replace('/', '_')

      param_io = 'argument4+learn+write:' \
        + os.path.join(model_dir, name + '.jser.gz')

      oracle_relations = 'event1,predicate2'

      job_name = tag + '-' + name
      args = [engine, '-N', job_name, '-cwd', '-o', logs, sh_omni, \
         wd, \
         arg4_grammar, \
         os.path.join(predictions_dir, name), \
         agenda_comparator, \
         oracle_relations, \
         param_io, \
         fs_stable, \
         gf_str, \
         train_method, \
         jar_stable]

      print args
      if not mock:
        subprocess.check_call(args)

  # DEPRECATED
  ### Test out various global feature and agenda priority combinations
  if 'arg4coarse' not in exp_names:
    print 'skipping arg4coarse'
  else:
    for gf in ['none', 'numArgs', 'argLoc', 'roleCooc', 'full']:
      a4_refs = ['argument4/t', 'argument4/s'] if gf != 'none' else ['argument4/t']
      for a4_ref in a4_refs:
        gf_str = a4_ref + '+' + gf
        for agenda_priority in weights_new():

          name = 'argment4'
          #name += '.autoPred' if auto_pred else '.goldPred'
          name += '.goldPred'
          name += '.' + gf_str.replace('/', '_')
          name += '.' + wpretty(agenda_priority)

          param_io = 'argument4+learn+write:' \
            + os.path.join(model_dir, name + '.jser.gz')

          oracle_relations = 'event1,predicate2'

          job_name = tag + '-' + name
          args = [engine, '-N', job_name, '-cwd', '-o', logs, sh_omni, \
             wd, \
             arg4_grammar, \
             os.path.join(predictions_dir, name), \
             wformat(agenda_priority), \
             oracle_relations, \
             param_io, \
             fs_stable, \
             gf_str, \
             train_method, \
             jar_stable]

          print args
          if not mock:
            subprocess.check_call(args)

