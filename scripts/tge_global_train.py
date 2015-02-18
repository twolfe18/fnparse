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
    cmd.append('-Xmx9G')
    cmd.append('-XX:+UseSerialGC')
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

    cmd.append('resultsReporter')
    reporters = 'redis:' + redis_config['host'] + ',' \
      + redis_config['channel'] + ',' + redis_config['port']
    reporters += '\tfile:' + os.path.join(wd, 'results.txt')
    reporters += '\tfile:' + os.path.join(self.working_dir_parent, 'results.txt')
    cmd.append(reporters)

    return cmd

def learning_curves(working_dir):
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
  
  lrBatchScale = 128
  for no_syntax in [False, True]:
    for oracleMode in ['RAND', 'MAX', 'MIN']:
      for n in [100, 500, 1500, 3000]:
        for batch_size in [1, 4]:
          for l2p in [1e-8, 1e-9, 1e-10]:
            cl = Config(working_dir)
            cl.noSyntax = no_syntax
            cl.oracleMode = oracleMode
            cl.lrBatchScale = lrBatchScale
            cl.l2Penalty = l2p
            cl.performPretrain = False
            cl.pretrainBatchSize = 1
            cl.trainBatchSize = batch_size
            cl.nTrain = n
            cl.useGlobalFeatures = False
            q_local.add(cl)
            for l2pg in [1e-6, 1e-7, 1e-8]:
              cg = Config(working_dir)
              cg.noSyntax = no_syntax
              cg.oracleMode = oracleMode
              cg.lrBatchScale = lrBatchScale
              cg.globalFeatRoleCooc = False
              cg.globalFeatCoversFrames = False
              cg.globalFeatArgLoc = True
              cg.globalFeatNumArgs = True
              cg.globalFeatArgOverlap = True
              cg.globalFeatSpanBoundary = True
              cg.l2Penalty = l2p
              cg.performPretrain = False
              cg.globalL2Penalty = l2pg
              cg.pretrainBatchSize = 1
              cg.trainBatchSize = batch_size
              cg.nTrain = n
              cg.useGlobalFeatures = True
              q_global.add(cg)

  print 'len(q_local) =', len(q_local)
  print 'len(q_global) =', len(q_global)
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


if __name__ == '__main__':
  if len(sys.argv) != 3:
    print 'please provide:'
    print '1) a working dir for output'
    print '2) a jar with all dependencies'
    sys.exit(-1)
  wd = sys.argv[1]
  Config.jar_file = sys.argv[2]

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

  #run(learning_curves(wd), wd, local=False)
  #run(fs_test(wd), wd, local=True)
  run(last_minute(wd), wd, local=True)




