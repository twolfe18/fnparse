#!/usr/bin/env python

import os
import shutil
import socket
import sys
import tge

# The purpose of this is to train a few Reranker models.
# I'm not doing feature selection, just trying a few hyperparams.
# For now, everything will be a flat queue.

# Currently we use redis for sending/receiving results
this_machine = socket.gethostname()
redis_config = {
  'channel': 'global-train',
  'host': this_machine,
  'port': '6379',
  'db': '0'
}

class Config(tge.Item):
  ''' Represents a parser configuration, stored in __dict__ '''

  # You can set this to override the 'find all jars in target/' behavior
  jar_file = None

  def __init__(self, working_dir_parent):
    '''
    working_dir_parent should be a directory that this
    Config's working dir will be placed into.
    '''
    self.working_dir_parent = working_dir_parent

  def jars(self):
    for f in os.listdir('target'):
      p = os.path.join('target', f)
      if os.path.isfile(p) and f.endswith('.jar'):
        yield p

  def build_command(self, name):
    cmd = []
    cmd.append('java')
    #cmd.append('-ea')
    cmd.append('-server')
    if self.nTrain <= 500:
      cmd.append('-Xmx7G')
    elif self.nTrain <= 1000:
      cmd.append('-Xmx9G')
    elif self.nTrain <= 1500:
      cmd.append('-Xmx11G')
    else:
      cmd.append('-Xmx15G')
    cmd.append('-XX:+UseSerialGC')
    cmd.append('-Dlog4j.configurationFile=/home/travis/code/fnparse/src/main/resources/log4j2.json')
    cmd.append('-cp')
    if Config.jar_file:
      cmd.append(Config.jar_file)
    else:
      cmd.append(':'.join(self.jars()))
    cmd.append('edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer')
    cmd.append(name)
    for k, v in self.__dict__.iteritems():
      assert isinstance(k, str)
      cmd += [k, str(v)]

    cmd.append('workingDir')
    wd = os.path.join(self.working_dir_parent, 'wd-' + name)
    cmd.append(wd)

    cmd.append('resultReporter')
    reporters = 'redis:' + redis_config['host'] + ',' \
      + redis_config['channel'] + ',' + redis_config['port']
    reporters += '\tfile:' + os.path.join(wd, 'results.txt')
    reporters += '\tfile:' + os.path.join(self.working_dir_parent, 'results.txt')
    cmd.append(reporters)

    return cmd

def last_last_minute(working_dir, real_test_set=False):
  if not os.path.isdir(working_dir):
    raise Exception('not a dir: ' + working_dir)

  q = tge.ExplicitQueue()
  q_local = q
  q_global = q

  for cost_fn in [1, 2, 4]:
    for batch_size in [1, 4]:
      for n in [9999, 100, 400, 1000, 2000]:
        for l2p in [1e-9, 1e-8]:
          for oracleMode in ['RAND_MAX', 'RAND_MIN', 'MAX', 'MIN']:
            for lh_most_violated in [False, True]:
              if lh_most_violated and oracleMode != 'MAX':
                # Choose a canonical oralceMode for forceLeftRightInference=True,
                # because they're all equivalent in that case.
                continue
              cl = Config(working_dir)
              cl.lhMostViolated = lh_most_violated
              cl.realTestSet = real_test_set
              cl.costFN = cost_fn
              cl.oracleMode = oracleMode
              cl.l2Penalty = l2p
              cl.performPretrain = False
              cl.trainBatchSize = batch_size
              cl.nTrain = n
              cl.useGlobalFeatures = False
              q_local.add(cl)
              for l2pg in [l2p * 10, l2p * 100]:
                cg = Config(working_dir)
                cg.lhMostViolated = lh_most_violated
                cg.realTestSet = real_test_set
                cg.costFN = cost_fn
                cg.oracleMode = oracleMode
                cg.globalFeatArgLoc = True
                cg.globalFeatNumArgs = True
                cg.globalFeatRoleCooc = True
                cg.l2Penalty = l2p
                cg.globalL2Penalty = l2pg
                cg.trainBatchSize = batch_size
                cg.nTrain = n
                cg.useGlobalFeatures = True
                q_global.add(cg)
  print 'len(q_local) =', len(q_local)
  print 'len(q_global) =', len(q_global)
  return q

def learning_curves(working_dir, real_test_set=False):
  ''' Returns a queue '''
  if not os.path.isdir(working_dir):
    raise Exception('not a dir: ' + working_dir)

  # Give local and global the same bandwidth
  #q = tge.MultiQueue()
  #q_local = q.add_queue('local', tge.ExplicitQueue())
  #q_global = q.add_queue('global', tge.ExplicitQueue())

  # First come first serve
  q = tge.ExplicitQueue()
  q_local = q
  q_global = q

  for cost_fn in [1, 2]:    # 2 usually wins, 1 can win at large N, never saw 4 win
    for batch_size in [1, 4]:
      for n in [9999, 100, 400, 1000, 2000]:
        for l2p in [1e-7, 1e-8, 1e-9, 1e-10]:
          for oracleMode in ['RAND_MAX', 'RAND_MIN', 'MAX', 'MIN']:
            for lh_most_violated in [False, True]:
              if lh_most_violated and oracleMode != 'MAX':
                # Choose a canonical oralceMode for forceLeftRightInference=True,
                # because they're all equivalent in that case.
                continue
              cl = Config(working_dir)
              cl.lhMostViolated = lh_most_violated
              cl.realTestSet = real_test_set
              cl.costFN = cost_fn
              cl.oracleMode = oracleMode
              cl.l2Penalty = l2p
              cl.performPretrain = False
              cl.trainBatchSize = batch_size
              cl.nTrain = n
              cl.useGlobalFeatures = False
              q_local.add(cl)
              for l2pg in [l2p * 10, l2p * 100, l2p * 1000]:
              #for l2pg in [1e-6, 1e-7, 1e-8, 1e-9]:
                cg = Config(working_dir)
                cg.lhMostViolated = lh_most_violated
                cg.realTestSet = real_test_set
                cg.costFN = cost_fn
                cg.oracleMode = oracleMode
                cg.globalFeatArgLoc = True
                cg.globalFeatNumArgs = True
                cg.globalFeatRoleCooc = True
                cg.l2Penalty = l2p
                cg.globalL2Penalty = l2pg
                cg.trainBatchSize = batch_size
                cg.nTrain = n
                cg.useGlobalFeatures = True
                q_global.add(cg)

  print 'len(q_local) =', len(q_local)
  print 'len(q_global) =', len(q_global)
  return q

def ablation2(working_dir, real_test_set=False):
  q = tge.ExplicitQueue()
  options = ['NumArgs', 'ArgLoc', 'ArgLocSimple', 'ArgOverlap', 'SpanBoundary', 'RoleCooc', 'RoleCoocSimple']
  options = ['globalFeat' + x for x in options]
  def use_only(conf, opt=None):
    for opt2 in options:
      conf.__dict__[opt2] = False
    if opt:
      conf.__dict__[opt] = True
  for n_train in [9999, 700]:
    # +nil
    c = Config(working_dir)
    c.realTestSet = real_test_set
    c.nTrain = n_train
    use_only(c)
    q.append(c)
    for opt in options:
      c = Config(working_dir)
      c.realTestSet = real_test_set
      c.nTrain = n_train
      use_only(c, opt)
      q.append(c)
  return q

def ablation(working_dir, real_test_set=False):
  # nothing, +arg-loc, +num-args, +role-cooc
  # take full from learning_curves run
  q = tge.ExplicitQueue()

  c_local = Config(working_dir)
  c_local.nTrain = 9999
  c_local.realTestSet = real_test_set
  c_local.useGlobalFeatures = False
  q.append(c_local)

  c_arg_loc = Config(working_dir)
  c_arg_loc.nTrain = 9999
  c_arg_loc.realTestSet = real_test_set
  c_arg_loc.globalFeatArgLoc = True
  c_arg_loc.globalFeatNumArgs = False
  c_arg_loc.globalFeatRoleCooc = False
  q.append(c_arg_loc)

  c_num_args = Config(working_dir)
  c_num_args.nTrain = 9999
  c_num_args.realTestSet = real_test_set
  c_num_args.globalFeatArgLoc = False
  c_num_args.globalFeatNumArgs = True
  c_num_args.globalFeatRoleCooc = False
  q.append(c_num_args)

  c_role_cooc = Config(working_dir)
  c_role_cooc.nTrain = 9999
  c_role_cooc.realTestSet = real_test_set
  c_role_cooc.globalFeatArgLoc = False
  c_role_cooc.globalFeatNumArgs = False
  c_role_cooc.globalFeatRoleCooc = True
  q.append(c_role_cooc)

  return q

def fs_test(working_dir):
  '''
  Returns a queue.
  Tests if the features chosen by feature-selection a while ago work
  better than the simple ones I put in the source code.
  '''
  if not os.path.isdir(working_dir):
    raise Exception('not a dir: ' + working_dir)

  q = tge.ExplicitQueue()
  for n in [100, 200, 400, 800, 1600]:
    for sf in [True, False]:
      c = Config(working_dir)
      c.performPretrain = False
      c.nTrain = n
      c.simpleFeatures = sf
      q.append(c)
  return q

def last_minute(working_dir):
  q = tge.MultiQueue()
  q_batch = q.add_queue('batch', tge.ExplicitQueue())
  q_cost_fn = q.add_queue('cost_fn', tge.ExplicitQueue())

  n = 150

  for batch_with_replacement in [True, False]:
    c = Config(working_dir)
    c.nTrain = n
    c.batchWithReplacement = batch_with_replacement
    q_batch.append(c)

  for cost_fn in [1, 2, 4, 8]:
    c = Config(working_dir)
    c.nTrain = n
    c.costFN = cost_fn
    q_cost_fn.append(c)

  return q


def run(q, working_dir, local=True):
  print 'running', q, 'and putting the results in', working_dir

  # Create the job tracker
  if local:
    d = os.path.join(working_dir, 'local-logs')
    job_tracker = tge.LocalJobTracker(max_concurrent_jobs=12, logging_dir=d)
    job_tracker.remove_all_jobs()
  else:
    d = os.path.join(working_dir, 'sge-logs')
    max_concur = 50
    job_tracker = tge.SgeJobTracker('twolfe', max_concur, logging_dir=d)


  print 'starting...'
  engine = tge.JobEngine('global-train', job_tracker, q, redis_config)
  engine.run(os.path.join(working_dir, 'results.txt'))


def str2bool(s):
  b = s.lower() in ['true', 't', '1']
  assert b or s.lower() in ['false', 'f', '0']
  return b

if __name__ == '__main__':
  if len(sys.argv) != 6:
    print 'please provide:'
    print '1) a working dir for output'
    print '2) a jar with all dependencies'
    print '3) a task to run (in \'ablation2\', \'last_last_minute\')'
    print '4) whether to run locally (\'True\') or on the grid (\'False\')'
    print '5) whether or not to run on the full test set'
    sys.exit(-1)
  wd = sys.argv[1]
  Config.jar_file = sys.argv[2]
  task = sys.argv[3]
  local = str2bool(sys.argv[4])
  full_test_set = str2bool(sys.argv[5])

  print 'task =', task
  print 'local =', local
  print 'full_test_set =', full_test_set
  print 'working_dir =', wd

  if not os.path.isdir(wd):
    raise Exception('wd must be dir: ' + wd)
  if not os.path.isfile(Config.jar_file):
    raise Exception('jar file must be file: ' + Config.jar_file)

  # Check if the jar is in the working dir, if not copy it in
  if not os.path.abspath(Config.jar_file).startswith(os.path.abspath(wd)):
    print 'moving jar into working directory so that it is stable'
    jn = os.path.basename(Config.jar_file)
    j = os.path.join(wd, jn)
    shutil.copyfile(Config.jar_file, j)
    Config.jar_file = j
    assert os.path.isfile(Config.jar_file)
    print 'now using jar=' + Config.jar_file

  #run(learning_curves(wd, True), wd, local=False)
  #run(fs_test(wd), wd, local=True)
  #run(last_minute(wd), wd, local=True)

  #mq = tge.MultiQueue()
  #mq.add_queue('learning_curves', learning_curves(wd, True))
  #mq.add_queue('ablation', ablation(wd, True))
  #run(mq, wd, local=False)

  if task == 'ablation2':
    run(ablation2(wd, full_test_set), wd, local=local)
  elif task == 'last_last_minute':
    run(last_last_minute(wd, full_test_set), wd, local=local)
  else:
    print 'unknown task:', task


