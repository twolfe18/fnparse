#!/usr/bin/env python

import os
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
    cmd.append(':'.join(self.jars()))
    cmd.append('edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer')
    cmd.append(name)
    for k, v in self.__dict__.iteritems():
      assert isinstance(k, str)
      cmd += [k, str(v)]
    cmd.append('resultsReporter')
    cmd.append('redis:' + redis_config['host'] + ',' \
      + redis_config['channel'] + ',' + redis_config['port'])
    cmd.append('workingDir')
    cmd.append(os.path.join(self.working_dir_parent, 'wd-' + name))
    return cmd

def learning_curves(working_dir):
  ''' Returns a queue '''
  if not os.path.isdir(working_dir):
    raise Exception('not a dir: ' + working_dir)
  q = tge.MultiQueue()
  q_local = q.add_queue('local', tge.ExplicitQueue())
  q_global = q.add_queue('global', tge.ExplicitQueue())
  
  for n in [100, 500, 1500, 3000]:
    for lrBatchScale in [1280, 128, 12800]:
      for batch_size in [1, 4, 16]:
        for l2p in [1e-6, 1e-8, 1e-10]:
          cl = Config(working_dir)
          cl.lrBatchScale = lrBatchScale
          cl.l2Penalty = l2p
          cl.pretrainBatchSize = 1
          cl.trainBatchSize = batch_size
          cl.nTrain = n
          cl.useGlobalFeatures = False
          q_local.add(cl)
          for l2pg in [1e-1, 1e-2, 1e-3]:
            for useRoleCooc in [True, False]:
              cg = Config(working_dir)
              cg.lrBatchScale = lrBatchScale
              cg.useRoleCooc = useRoleCooc
              cg.l2Penalty = l2p
              cg.globalL2Penalty = l2pg
              cg.pretrainBatchSize = 1
              cg.trainBatchSize = batch_size
              cg.nTrain = n
              cg.useGlobalFeatures = True
              q_global.add(cg)

  return q

def run(q, working_dir, local=True):
  print 'running', q, 'and putting the results in', working_dir

  # Create the job tracker
  if local:
    job_tracker = tge.LocalJobTracker(max_concurrent_jobs=2)
    job_tracker.remove_all_jobs()
  else:
    d = os.path.join(working_dir, 'sge-logs')
    max_concur = 50
    job_tracker = tge.SgeJobTracker('twolfe', max_concur, logging_dir=d)


  print 'starting...'
  engine = tge.JobEngine('global-train', job_tracker, q, redis_config)
  engine.run(os.path.join(working_dir, 'results.txt'))


if __name__ == '__main__':
  if len(sys.argv) != 2:
    print 'please provide a working dir'
    sys.exit(-1)
  wd = sys.argv[1]
  run(learning_curves(wd), wd, local=False)




