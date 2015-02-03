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
  def jars(self):
    for f in os.listdir('target'):
      p = os.path.join('target', f)
      if os.path.isfile(p) and f.endswith('.jar'):
        yield p
  def build_command(self, name):
    cmd = []
    cmd.append('java')
    cmd.append('-ea')
    cmd.append('-Xmx6G')
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
    return cmd

def learning_curves():
  ''' Returns a queue '''
  q = tge.MultiQueue()
  q_local = q.add_queue('local', tge.ExplicitQueue())
  q_global = q.add_queue('global', tge.ExplicitQueue())
  
  for n in [100, 500, 1500, 3000]:
    cl = Config()
    cl.nTrain = n
    cl.useGlobalFeatures = False
    q_local.add(cl)

    cg = Config()
    cg.nTrain = n
    cg.useGlobalFeatures = True
    q_global.add(cg)

  return q

def run(q, working_dir, local=True):
  #working_dir = '/tmp/tge-global-train'
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
    #print 'make sure its a full path! sge is not the sharpest...'
    sys.exit(-1)
  wd = sys.argv[1]
  run(learning_curves(), wd, local=False)




