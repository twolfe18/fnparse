
import os, sys, subprocess, shutil, math, collections


def logit(x):
  return 1.0 / (1 + math.exp(-x))

def local_cheat_probs():
  ''' In the VFP inconsistency experiments, I allow the local features
      to cheat by firing with f(action)=[+1/-1] for cost(action)=0,
      with some probability of flipping the answer. This functioni returns
      a nice range of probs to try. '''
  return [logit(x/4.0) for x in range(12)]


def reorder_methods():
  ''' works with hacky implementation, see comparators below
      for how to vary this with the regular/permanent implementation
  '''
  # NONE,               // leave order as T.then(F).then(K)
  # CONF_ABS,           // score(bucket) = max_{f in bucket} score(f)
  # CONF_ABS_NON_NIL,   // score(bucket) = max_{f in bucket and f is not nil} score(f)
  # CONF_REL,           // score(bucket) = max_{f in bucket} score(f) - secondmax_{f in bucket} score(f)
  # CONF_REL_NON_NIL,   // score(bucket) = max_{f in bucket and f is not nil} diff(score(f), score(nil))
  return ['NONE', 'CONF_ABS', 'CONF_ABS_NON_NIL']

def agenda_comparators():
  ''' works with the key: agendaComparator (real impl, not hacky)
      see uberts/AgendaComparators.java
  '''
  yield 'BY_RELATION,BY_TARGET,BY_FRAME,BY_ROLE,BY_SCORE'
  yield 'BY_RELATION,BY_TARGET,BY_FRAME,BY_SCORE'
  yield 'BY_RELATION,BY_SCORE'


def arg4t_global_feature_configs(include_full=True, only_best_refinement=False):
  ''' put the best refinement first '''
  d = {}
  d['+none'] = ['@CONST']
  d['+roleCooc'] = ['@F', '@CONST', '@F_1']
  d['+numArgs'] = ['@F', '@F_1', '@FK', '@FK_F']
  d['+argLocRoleCooc'] = ['@F', '@F_1', '@FK', '@FK_1', '@K', '@K_F']
  d['+argLocPairwise'] = ['@F', '@F_1', '@FK', '@FK_1', '@K', '@K_F']
  #d['+argLocGlobal'] = ['@F', '@F_1', '@FK', '@FK_1', '@K', '@K_F']

  if include_full:
    d['+full'] = ['@F', '@F_1', '@FK', '@FK_1', '@K', '@K_F']

  for gf, refs in d.iteritems():
    if only_best_refinement:
      yield 'argument4/t' + gf + refs[0]
    else:
      for r in refs:
        yield 'argument4/t' + gf + r

def arg4s_global_feature_configs():
  raise Exception('figure out what these should be')


# DEPRECATED
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


def naacl_workshop_action_orderings(only_default=False, easy_first=False):
  order_prefix = 'BY_RELATION,BY_TARGET,BY_FRAME'
  order_suffixes = [ \
    'BY_ROLE_FREQ', \
    'BY_EASYFIRST_STATIC', \
    'BY_EASYFIRST_DYNAMIC', \
    'BY_RAND_STATIC', \
    'BY_RAND_DYNAMIC', \
  ]
  if easy_first and only_default:
    raise Exception()
  if only_default:
    order_suffixes = [order_suffixes[0]]
  if easy_first:
    order_suffixes = [order_suffixes[2]]
  return [order_prefix + ',' + x + ',BY_SCORE' for x in order_suffixes]

def naacl_workshop(wd, fs_stable, predictions_parent_dir, omni_script, log_dir, jar_stable, engine='qsub'):
  print '[naacle_workshop] starting...'

  role_cooc = 'argument4/t+roleCooc@F'

  def is_vfp(train_method):
    if train_method in ['MAX_VIOLATION', 'LATEST_UPDATE']:
      return True
    if train_method in ['LASO2']:
      return False
    raise Exception('unknown: ' + str(train_method))

  # A counter for types of jobs
  jc = collections.defaultdict(int)

  # Not writing out any models for now
  param_io = ''

  # Figure 1 and 2: 8*6 = 48 jobs
  # [VFP, LOLS] x [freq]            x [gold f, auto f]  x [global feats]  x [pb, fn]
  for oracle_relations in ['event1,predicate2', 'event1']:
    for dataset in ['framenet', 'propbank']:
      arg4_grammar = "data/srl-reldata/grammar/srl-grammar-%s.trans" % (dataset,)
      if not os.path.isfile(arg4_grammar):
        raise Exception('no grammar: ' + arg4_grammar)
      # Currently I only show MAX_VIOLATION and LASO2, but include LATEST_UPDATE just in case
      for train_method in ['MAX_VIOLATION', 'LATEST_UPDATE', 'LASO2']:
        for gf_str in arg4t_global_feature_configs(only_best_refinement=True):
          for action_ordering in naacl_workshop_action_orderings(only_default=True):
            jc['fig[12]'] += 1
            #pred_dir = os.path.join(predictions_parent_dir, '???')
            pred_dir = 'none'
            job_name = 'fig12'
            args = [engine, '-N', job_name, '-cwd', '-o', log_dir, omni_script, \
              wd, \
              arg4_grammar, \
              pred_dir, \
              action_ordering, \
              oracle_relations, \
              param_io, \
              fs_stable, \
              gf_str, \
              train_method, \
              jar_stable]
            print ' '.join(args)

  # Figure 3: ~24 jobs
  # [VFP]       x [freq]            x [gold f]          x [roleCooc]      x [pb, fn]  x local_cheat_probs()
  oracle_relations = 'event1,predicate2'
  for dataset in ['framenet', 'propbank']:
    arg4_grammar = "data/srl-reldata/grammar/srl-grammar-%s.trans" % (dataset,)
    train_method = 'MAX_VIOLATION'
    gf_str = role_cooc
    for action_ordering in naacl_workshop_action_orderings(only_default=True):
      for p_local_cheat in local_cheat_probs():
        jc['fig3'] += 1
        #pred_dir = os.path.join(predictions_parent_dir, '???')
        pred_dir = 'none'
        job_name = 'fig3'
        args = [engine, '-N', job_name, '-cwd', '-o', log_dir, omni_script, \
          wd, \
          arg4_grammar, \
          pred_dir, \
          action_ordering, \
          oracle_relations, \
          param_io, \
          fs_stable, \
          gf_str, \
          train_method, \
          jar_stable, \
          "argument4.softLocalOracle %f" % (p_local_cheat,)]
        print ' '.join(args)

  # Figure 4: ~10 jobs
  # [LOLS]      x reorder_methods() x [gold f]          x [roleCooc]      x [pb, fn] oracle_relations = 'event1,predicate2'
  for dataset in ['framenet', 'propbank']:
    arg4_grammar = "data/srl-reldata/grammar/srl-grammar-%s.trans" % (dataset,)
    train_method = 'LASO2'
    gf_str = role_cooc
    for action_ordering in naacl_workshop_action_orderings():
      jc['fig4'] += 1
      #pred_dir = os.path.join(predictions_parent_dir, '???')
      pred_dir = 'none'
      job_name = 'fig4'
      args = [engine, '-N', job_name, '-cwd', '-o', log_dir, omni_script, \
        wd, \
        arg4_grammar, \
        pred_dir, \
        action_ordering, \
        oracle_relations, \
        param_io, \
        fs_stable, \
        gf_str, \
        train_method, \
        jar_stable]
      print ' '.join(args)

  # Figure 5: 4 jobs
  # [VFP]       x [easyfirst]       x [gold f, auto f]  x [roleCooc]      x [pb, fn]
  for oracle_relations in ['event1,predicate2', 'event1']:
    for dataset in ['framenet', 'propbank']:
      arg4_grammar = "data/srl-reldata/grammar/srl-grammar-%s.trans" % (dataset,)
      # I could leave LASO2 off here (and get LASO2 from other experiment), but it doesn't add many jobs
      for train_method in ['MAX_VIOLATION', 'LATEST_UPDATE', 'LASO2']:
        gf_str = role_cooc
        acot = [True, False] if is_vfp(train_method) else [False]
        for add_class_obj_term in acot:
          for action_ordering in naacl_workshop_action_orderings(easy_first=True):
            jc['fig5'] += 1
            #pred_dir = os.path.join(predictions_parent_dir, '???')
            pred_dir = 'none'
            job_name = 'fig5'
            args = [engine, '-N', job_name, '-cwd', '-o', log_dir, omni_script, \
              wd, \
              arg4_grammar, \
              pred_dir, \
              action_ordering, \
              oracle_relations, \
              param_io, \
              fs_stable, \
              gf_str, \
              train_method, \
              jar_stable, \
              "includeClassificationObjectiveTerm %s" % (add_class_obj_term,)]
            print ' '.join(args)

  # Figure 6: 8 jobs
  # [LOLS]      x [easyfirst]       x [gold f, auto f]  x [roleCooc]      x [pb, fn]  x [hammingCost, svmCost]
  for oracle_relations in ['event1,predicate2', 'event1']:
    for dataset in ['framenet', 'propbank']:
      arg4_grammar = "data/srl-reldata/grammar/srl-grammar-%s.trans" % (dataset,)
      for cost in ['HAMMING', 'HINGE']:
        train_method = 'LASO2'
        gf_str = role_cooc
        for action_ordering in naacl_workshop_action_orderings(easy_first=True):
          jc['fig6'] += 1
          #pred_dir = os.path.join(predictions_parent_dir, '???')
          pred_dir = 'none'
          job_name = 'fig6'
          args = [engine, '-N', job_name, '-cwd', '-o', log_dir, omni_script, \
             wd, \
             arg4_grammar, \
             pred_dir, \
             action_ordering, \
             oracle_relations, \
             param_io, \
             fs_stable, \
             gf_str, \
             train_method, \
             jar_stable,
             "costMode %s" % (cost,)]
          print ' '.join(args)

  print 'job counts:', jc
  print 'total', sum(jc.values())



if __name__ == '__main__':
  if len(sys.argv) != 6:
    print 'please provide:'
    print '1) an exeperiment string, comma separated, (naacl_workshop|pred2|arg4fine|arg4coarse)'
    print '2) an unique experiment name'
    print '3) a working directory'
    print '4) a feature set directory with <relationName>.fs files'
    print '5) a JAR file (to be copied)'
    sys.exit(1)

  exp_names, tag, wd, fs, jar = sys.argv[1:]

  exp_names = set(x.strip().lower() for x in exp_names.split(','))

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
           "agendaComparator '%s'" % (agenda_priority,), \
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

  if 'naacl_workshop' not in exp_names:
    print 'skipping naacl_workshop'
  else:
    naacl_workshop(wd, fs_stable, predictions_dir, sh_omni, logs, jar_stable, engine)

  ### Test out different types of Fy for various global features
  if 'arg4fine' not in exp_names:
    print 'skipping arg4fine'
  else:
    for hacky_reorder in reorder_methods():
      reorder = 'hackyTFKReorderMethod ' + hacky_reorder
    #for agenda_comparator in agenda_comparators():
      #reorder = 'agendaComparator ' + agenda_comparator
      for gf_str in arg4t_global_feature_configs():
        name = 'a4fine'
        name += '.' + gf_str.replace('/', '_')
        name += '.' + hacky_reorder
        name = name.replace('@', '-AT-')
        name = name.replace('+', '-PLUS-')

        param_io = 'argument4+learn+write:' \
          + os.path.join(model_dir, name + '.jser.gz')

        oracle_relations = 'event1,predicate2'

        job_name = tag + '-' + name
        args = [engine, '-N', job_name, '-cwd', '-o', logs, sh_omni, \
           wd, \
           arg4_grammar, \
           os.path.join(predictions_dir, name), \
           reorder, \
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
             "agendaComparator '%s'" % (wformat(agenda_priority),), \
             oracle_relations, \
             param_io, \
             fs_stable, \
             gf_str, \
             train_method, \
             jar_stable]

          print args
          if not mock:
            subprocess.check_call(args)

