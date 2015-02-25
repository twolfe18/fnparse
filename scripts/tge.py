#!/usr/bin/env python

import collections
import math
import operator
import os
import pickle
import random
import redis
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


# sorry, global
FAILED_SCORE = 0.0


# JobEngine: listens to messages, pops items from a Queue, names jobs, and asks the JobTracker to start the job
# JobTracker: reports the state of the computation resource (grid), w.r.t. a user (possibly including items from multiple Queues)
# ItemToJobConverter belongs to JobEngine and bridges the gap between items (from Queue) and jobs (sent to JobTracker)

class Item(object):
  ''' Basic unit representing one setting or run of an experiment '''
  def build_command(self, name):
    '''
    Return a list of strings representing a command that will evaluate/run this
    item. This job must report the result back to the JobEngine via redis.
    name is the name for this item givne by JobEngine and MUST be used by the
    resulting command to communicate back to JobEngine. Typically this name
    will be passed to your experiment code as the first argument, then the
    experiment code will use something like
    edu.jhu.hlt.fnparse.experiment.grid.ResultReporter.Redis to communicate
    back to JobEngine.
    name is also useful for creating a unique name for a working directory
    (for a job to dump output data, logs, etc), as the JobEngine will never
    assign two jobs the same name.
    '''
    raise NotImplementedError()


class Queue(object):
  ''' Provides items to JobEngine '''
  def observe_score(self, score, name, item):
    ''' Receive a message about the final score of an item running '''
    #raise NotImplementedError()
    pass
  def pop(self):
    raise NotImplementedError()


class ExplicitQueue(Queue):
  ''' The simplest queue: a finite list of items (to pop in order) '''
  def __init__(self, items=None):
    if items:
      self.items = items
    else:
      self.items = []
  def __len__(self):
    return len(self.items)
  def add(self, item):
    self.items.append(item)
  def append(self, item):
    self.items.append(item)
  def pop(self):
    if self.items:
      r = self.items[0]
      self.items = self.items[1:]
      print '[ExplicitQueue] pop', len(self.items), 'left'
      return r
    else:
      return None


class Mutator(object):
  ''' Mutates items, only serves MutatorQueue '''
  def mutate(self, item):
    ''' return a list of items derived from the given item '''
    raise NotImplementedError()


class MutatorQueue(Queue):
  '''
  An infinite queue of items. A seed set of items should be pushed onto the queue
  to begin with, but after that this queue will generate new items by calling
  propose_modification on existing items. Remember to call observe_score to let this
  queue know what are good items and which are bad.
  '''
  def __init__(self, mutator, greediness=1.0, max_pops=0):
    self.__mutator = mutator  # see Mutator.mutate, item -> [item]
    self.greediness = greediness
    self.item2score = {}    # item -> score for popped items
    self.waiting = []       # List of items that should be popped before mutating scored items
    self.__max_pops = max_pops
    self.__pop_count = 0
    self.stopped = False

  def _pick(self, greediness):
    debug = len(self.item2score) < 25
    # Sort the items by score, and find the max score
    weights = []
    items = []
    x = sorted(self.item2score.items(), key=lambda kv: kv[1], reverse=True)
    m = x[0][1]
    z = 0.0
    rank = 0
    for item, s in x:
      regret_a = (m - s) * 10.0
      regret_b = math.sqrt(rank) / 10.0
      w = math.exp(-greediness * (regret_a + regret_b))
      weights.append(w)
      items.append(item)
      z += w
      rank += 1
    t = random.random() * z
    if debug:
      print '[pick] z =', z, 't =', t
    # Randomly sample
    c = 0.0
    for i in range(len(weights)):
      c += weights[i]
      if debug:
        print '[pick]', items[i], 'has score', c
      if c >= t:
        print '[pick] chose', items[i]
        return items[i]
    # Should never get here
    print '[pick] scored_feature_sets:', self.item2score
    print '[pick] weights:', weights
    assert False

  def message(self, message):
    if message == 'stop':
      self.stopped = True
    else:
      print '[MutatorQueue message] unknown message:', message

  def show_state(self, k=10):
    print '[show_state]', len(self.item2score), 'observations'
    print '[show_state] stopped:', self.stopped
    best = sorted(self.item2score.items(), key=operator.itemgetter(1), reverse=True)
    i = 1
    for item, score in best[:k]:
      print "[show_state] %dth best\t%.3f\t%s" % (i, score, item)
      i += 1

  def observe_score(self, score, name, item):
    ''' Record the score of a given config/item '''
    print '[observe_score] score =', score, 'item =', item
    assert type(score) is float
    assert type(name) is str
    assert type(item) is not str  # TODO for debugging, in the future this could technically be a string

    try:
      item.score = score
    except:
      print '[observe_score] this item type does not have a score attribute:', type(item)

    if item in self.item2score:
      if self.item2score[item] == FAILED_SCORE:
        print ("[observe_score] WARN: may need to sleep longer, " \
          "%s/%s was originally deemed failed but later reported a score of %f") \
          % (item, name, score)
      else:
        raise Exception(str(item) + '/' + name + ' had a score of ' \
          + str(self.item2score[item]) \
          + ' but you tried to observe the score ' + str(score))
    self.item2score[item] = score
    # print the biggest weights
    if len(self.item2score) % 50 == 0:
      self.show_state()

  def seed(self, item):
    ''' Like push, but this item is special in that we will use it as the seed
    for all items until we start receiving scores back '''
    print '[seed] seeding', item, 'with score', FAILED_SCORE
    self.push(item)
    self.observe_score(FAILED_SCORE, 'seed' + str(len(self.item2score)), item)

  def push(self, item):
    ''' Push an item onto the queue which must be run before
    propose_modification's are considered '''
    self.waiting.append(item)

  def pop(self):
    ''' Returns a pushed item if there is one, otherwise chooses
    a scored item and mutates it with propose_modification '''
    if self.stopped:
      print '[pop] MutatorQueue is stopped, returning None'
      return None
    if self.__max_pops > 0 and self.__pop_count >= self.__max_pops:
      print '[pop]', str(self), 'hit max pops:', self.__max_pops
      return None
    self.__pop_count += 1
    if self.waiting:
      print '[pop] from waiting'
      return self.waiting.pop()
    else:
      if len(self.item2score) == 0:
        raise Exception('you must seed some items using push so there is something to mutate')
      tries = 0
      max_tries = 100
      while tries < max_tries:
        # g->0 as we run out of tries
        g = self.greediness * (max_tries - tries) / float(max_tries)
        parent = self._pick(g)
        children = self.__mutator.mutate(parent)
        dont_generate = set(self.waiting + self.item2score.keys())
        feasible = [c for c in children if c not in dont_generate]
        if len(feasible) < len(children):
          for c in (set(children) - set(feasible)):
            print '[pop] pruned', c
        if feasible:
          random.shuffle(feasible)
          while len(feasible) > 1:
            self.push(feasible.pop())
          print '[pop] after', tries, 'tries'
          return feasible.pop()
        else:
          tries += 1
      print '[pop] couldn\'t generate a new configuration'
      return None


class MultiQueue(object):
  ''' Muxes multiple Queues into one Queue (round-robin) '''
  def __init__(self):
    self.__item2qn = {}
    self.__name2q = {}
    self.__q_names = []
    self.__on_deck = 0
    self.__stopped = set()  # names of queues that are paused/stopped
    self.__all_stopped = False

  def add_queue(self, name, queue):
    ''' adds this queue to the round-robin and returns the given queue '''
    assert name not in self.__name2q
    self.__name2q[name] = queue
    self.__q_names.append(name)
    return queue

  def message(self, message):
    print '[MultiQueue message]', message
    if message == 'list' or message == 'info':
      if self.__all_stopped:
          print '[MultiQueue message list] all stopped:', self.__q_names
      elif self.__q_names:
        for q in self.__q_names:
          r = 'stopped' if q in self.__stopped else 'running'
          print '[MultiQueue message list]', q, 'is', r
      else:
        print '[MultiQueue message list] no queues!'
    elif message == 'stop':
      self.__all_stopped = True
    elif message == 'start':
      self.__all_stopped = False
    elif message == 'help':
      print '[MultiQueue message help] allowable commands:'
      print 'list: list all queues'
      print 'help: this command'
      print 'stop: pause all queues'
      print 'start: resume all queues'
      print '[queue_name] [command]: dispatch command to queue_name'
    else:
      # queue-specific message
      try:
        qn, msg = message.split(' ', 1)
        if msg == 'stop':
          print '[MultiQueue message] stopping', qn
          self.__stopped.add(qn)
        elif msg == 'start':
          print '[MultiQueue message] starting', qn
          self.__stopped.remove(qn)
        else:
          print '[MultiQueue message] forwarding', msg, 'to', qn
          q = self.__name2q[qn]
          q.message(msg)
      except:
        print '[MultiQueue message] failed to handle message:', message

  def observe_score(self, score, name, item):
    # parse name, lookup queue, forward call
    qn = self.__item2qn[item]
    q = self.__name2q[qn]
    q.observe_score(score, name, item)

  def __nextq(self):
    n = len(self.__q_names)
    if self.__on_deck == n - 1:
      self.__on_deck = 0
    else:
      self.__on_deck += 1

  def pop(self):
    if self.__all_stopped:
      print '[MultiQueue pop] stopped, returning None'
      return None
    n = len(self.__q_names)
    assert self.__on_deck < n
    for i in range(n):
      qn = self.__q_names[self.__on_deck]
      if qn in self.__stopped:
        self.__nextq()
      else:
        q = self.__name2q[qn]
        p = q.pop()
        self.__nextq()
        if p:
          self.__item2qn[p] = qn
          return p
    print '[MultiQueue pop] returning None', len(self.__stopped), 'stopped queues'
    return None


class SgeJobTracker(object):
  '''
  A job tracker that asks qstat for the jobs that are running and spawns jobs with
  qsub. This class only works for a single user and expects to be able to have full
  control over the jobs that that user run.
  '''
  def __init__(self, sge_user, max_concurrent_jobs, logging_dir=None):
    self.sge_user = sge_user
    self.max_concurrent_jobs = max_concurrent_jobs
    self.logging_dir = logging_dir
    if logging_dir and not os.path.isdir(logging_dir):
      os.makedirs(logging_dir)

  def can_submit_more_jobs(self):
    if not self.max_concurrent_jobs:
      return True
    try:
      return len(self.jobs_queued()) < self.max_concurrent_jobs
    except subprocess.CalledProcessError:
      return False

  def jobs(self):
    '''
    name_predicate should be a lambda that takes a string (name)
    and returns true if the job should be kept.
    This method skips over any jobs that are marked as QLOGIN,
    so name_predicate need not filter those out.
    Returns a list of job names.
    '''
    xml = subprocess.check_output(['qstat', '-u', self.sge_user, '-xml'])
    xml = ET.fromstring(xml)
    assert xml.tag == 'job_info'
    # NOTE: wow this is really bad...
    # SGE reports *running* jobs in a list called 'queue_info'
    # and reports *queued* jobs in a list called 'job_info'
    for info_name in ['job_info', 'queue_info']:
      info = xml.find(info_name)
      assert info is not None
      # NOTE: each 'job_list' is actually a job
      # not a list of jobs as the name would suggest
      for j in info.findall('job_list'):
        #print 'j.tag', j.tag
        state = j.find('state').text    # e.g. 'r' or 'qw'
        name = j.find('JB_name').text
        #print '[sge jobs]', state, name
        if name == 'QLOGIN':
          continue
        yield (state, name)

  def jobs_running(self):
    ''' Returns a list of job names '''
    return [name for state, name in self.jobs() if state == 'r']

  def jobs_queued(self):
    ''' Returns a list of job names '''
    return [name for state, name in self.jobs() if state == 'qw']

  def spawn(self, name, args):
    '''
    Expects a command to run (args) which is a list of strings.
    name should be a string that will be used for the SGE job name.
    '''
    assert isinstance(args, list)
    assert isinstance(name, str)
    cmd = ['qsub', '-N', name, '-j', 'y', '-V', '-b', 'y', '-cwd']

    # If there is an arg that looks like Xmx, then use that to set mem_free
    xmx = None
    for a in args:
      m = re.match('^-Xmx(\d+)[gG]$', a)
      if m:
        if xmx:
          xmx = None
          print '[sge spawn] WARN: you set Xmx twice!'
          break
        xmx = int(m.group(1)) + 1
    if not xmx:
      xmx = 10 # Gb

    cmd += ['-q', 'all.q', '-l', 'num_proc=1,mem_free=' + str(xmx) + 'G,h_rt=72:00:00']
    if self.logging_dir:
      cmd += ['-o', self.logging_dir]
    cmd += args
    print '[sge spawn] cmd =', ' '.join(cmd)
    subprocess.Popen(cmd)
    time.sleep(0.2)


class LocalJobTracker(object):
  '''
  A mock job tracker which uses redis instead of qsub.
  '''
  def __init__(self, max_concurrent_jobs=2, logging_dir=None):
    self.key = 'dummy-job-tracker.jobs'
    self.redis = redis.StrictRedis(host='localhost', port=6379, db=0)
    self.max_concurrent_jobs = max_concurrent_jobs

    # Need to open file descriptors to redirect stdout from launched
    # jobs to a file (similar to how SGE does logging). I give popen
    # a fd at spawn and then close the df at set_job_done.
    self.name2fd = {}
    if logging_dir and not os.path.isdir(logging_dir):
      os.mkdir(logging_dir)
    self.logging_dir = logging_dir

  def remove_all_jobs(self):
    ''' ensures that there are no jobs running (for testing) '''
    self.redis.delete(self.key)

  def can_submit_more_jobs(self):
    return len(self.jobs_running()) < self.max_concurrent_jobs

  def jobs_running(self):
    # TODO this won't work in cases where jobs die!
    # qsub can handle this, but to remove from redis queue, we've been assuming things finish
    return self.redis.lrange(self.key, 0, -1)

  def set_job_done(self, name):
    print '[LocalJobTracker set_job_done] name=' + name
    self.redis.lrem(self.key, 0, name)

    # Try to close the fd associated with this jobs log
    try:
      fd = self.name2fd[name]
      fd.close()
    except Exception as e:
      print name, 'caused', e

  def jobs_queued(self):
    return []

  def spawn(self, name, args):
    assert isinstance(args, list)
    assert isinstance(name, str)
    self.redis.rpush(self.key, name)
    print '[LocalJobTracker spawn] about to spawn:', ' '.join(["'" + x + "'" for x in args])
    if self.logging_dir:
      log_name = os.path.join(self.logging_dir, name + '.log')
      log_fd = open(log_name, 'w')
      self.name2fd[name] = log_fd
      print '[LocalJobTracker spawn]', name, 'is using log file', log_name, log_fd
      subprocess.Popen(args, stdout=log_fd, stderr=log_fd)
    else:
      subprocess.Popen(args)


class JobEngine:
  ''' The quarterback of tge '''

  def __init__(self, name, job_tracker, item_queue, redis_config, poll_interval=8.0):
    print '[JobEngine] attempting to use redis server at', redis_config
    self.name = name
    self.job_tracker = job_tracker    # talks to qsub
    self.item_queue = item_queue          # provides pop() and observe_score(score, name, item)
    self.redis_config = redis_config
    self.name2item = {}
    self.poll_interval = poll_interval
    self.dispatched = set()           # names of the jobs that (should be) running

  def parse_message(self, data):
    '''
    Parses a message from the experiment over redis pubsub and returns a tuple of
    (config, score)
    '''
    toks = data.split('\t', 2)
    assert len(toks) == 3
    score = float(toks[0])
    name = toks[1]
    config = toks[2]
    return (score, name, config)

  def start_job(self):
    ''' Returns a unique job name and updates self.name2item '''
    item = self.item_queue.pop()
    if item:
      # TODO this name needs to start with the queue name so MultiQueue knows
      # what to do when you call .observe_score()
      name = "fs-%s-%d" % (self.name, len(self.name2item))
      assert name not in self.name2item
      self.name2item[name] = item
      command = item.build_command(name)
      self.job_tracker.spawn(name, command)
      self.dispatched.add(name)
      print '[start_job] name =', name
      print '[start_job] command =', command
      return name
    else:
      print '[start_job] there are no more jobs, returning None'
      return None

  def __handle_message(self, message, perf_file):
    ''' NOTE there is no command for 'stop'. This is because you should use 'messageQ',
    tell it to 'stop', and then this loop will exit when all the jobs are done. '''
    toks = message.split(' ', 1)
    if len(toks) == 1:
      print '[__handle_message] unknown message type:', message
      return
    m_type, rest = toks
    if m_type == 'result':
      # parse out (score, name, config), do what we were doing before
      score, name, config = rest.split('\t', 2)
      score = float(score)
      item = self.name2item[name]
      print '[__handle_message]', name, '/', config, 'finished successfully with a score', score
      perf_file.write("%f\t%s\t%s\n" % (score, name, config))
      perf_file.flush()
      self.item_queue.observe_score(score, name, item)
      # Remove this jobs from dispatched
      print '[__handle_message] about to print job tracker'
      print '[__handle_message]', self.job_tracker
      if type(self.job_tracker) is LocalJobTracker: # for debugging
        print '[__handle_message] stopping', name, 'on local job tracker'
        self.job_tracker.set_job_done(name)
      try:
        self.dispatched.remove(name)
        print '[__handle_message] removed', name, 'from dispatched,', len(self.dispatched), 'still dispatched'
      except:
        print '[__handle_message]', name, 'was not in dispatched, we gave up on this job as failed previously'
    elif m_type == 'messageQ':
      # check for a queue name argument, forward to q.message(remaining_message)
      try:
        self.item_queue.message(rest)
      except (AttributeError, TypeError) as e:
        print '[__handle_message] this queue does not support messages:', rest
        print e
    elif m_type == 'saveQ':
      # parse out file, pickle the queue to that
      print '[__handle_message] saving queue to', rest
      with open(rest, 'wb') as f:
        pickle.dump(self.item_queue, f)
    elif m_type == 'loadQ':
      # parse out file, unpickle the queue from that
      print '[__handle_message] loading queue from', rest
      with open(rest, 'rb') as f:
        self.item_queue = pickle.load(f)
      self.item_queue.show_state()
    else:
      print '[__handle_message] unknown message:', message

  def run(self, perf_file_name):
    r = redis.StrictRedis(host=self.redis_config['host'], port=self.redis_config['port'], db=self.redis_config['db'])
    p = r.pubsub(ignore_subscribe_messages=True)
    p.subscribe(self.redis_config['channel'])
    print '[run] writing results to', perf_file_name
    perf_file = open(perf_file_name, 'w')
    self.num_jobs = 0
    while True:
      # Try to dispatch new jobs
      can_sub = self.job_tracker.can_submit_more_jobs()
      if can_sub and self.start_job():
        self.num_jobs += 1
      else:
        # Check for results 
        if can_sub:
          print '[run] can submit more jobs, but queue is empty'
        else:
          print '[run] can\'t submit any more jobs'
        print '[run] checking on current jobs'
        message = p.get_message()
        if message:
          print '[run] received message:', message
          self.__handle_message(message['data'], perf_file)
        else:
          # check if any jobs died
          time.sleep(self.poll_interval)
          print '[run] no one phoned home, maybe someone died?'
          try:
            r = set(self.job_tracker.jobs_running() + self.job_tracker.jobs_queued())
            if len(r) == 0 and can_sub:
              print '[run] all jobs are done!'
              break
            print '[run] running and queued:', r
            failed = set([name for name in self.dispatched if name not in r])
            for name in failed:
              item = self.name2item[name]
              print '[run] dead:', name, item
              self.item_queue.observe_score(FAILED_SCORE, name, item)
              self.dispatched.remove(name)
            else:
              print '[run] everything is running nicely:', len(r)
          except subprocess.CalledProcessError:
            print '[run] qstat failed! ignoring...'
    perf_file.close()


