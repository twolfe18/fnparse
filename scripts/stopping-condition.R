
### See documentation at the bottom of this file!
### Travis Wolfe (twolfe18@gmail.com)
### February 3, 2015

# Returns true if the history up to this point looks like you should stop
# alpha is the required confidence for lowering the 
stop = function(y, alpha, k=60, inf.window=F, plot.t=F) {
  n = length(y)
  x = 1:n
  inf.window.decay = 10 * k
  t.star = qt(1 - alpha, k - 1)  # significant @ alpha if t > t.star

  debug = T
  if (inf.window) {
    rel = 1:n
    w = exp(-(n - x)**2 / inf.window.decay)
    m = lm(y ~ x, subset=rel, weights=w)
    if (debug)
      print(cbind(y, w))
  } else {
    rel = (n-k):n
    w = ifelse(x >= (n-k), 1, 0)
    m = lm(y ~ x, subset=rel)
    if (debug)
      print(y[rel])
  }

  new.way = T
  if (new.way) {
    a = anova.lm(m)
    p = a["Pr(>F)"][1,1]
    if (debug)
      print(paste("p", p, "alpha", alpha))
    return(p > alpha)
  } else {
    p = predict(m, data.frame(x=c(n+1)), se.fit=T)

    # Compare this prediction to the noise we've seen.
    # We should be confident that our next step will be
    # lower than our previous samples *in expectation*.
    # var = E[x^2] - E[x]^2
    #     = E[x^2 - mu^2]
    mu = weighted.mean(y, w)
    se = weighted.mean(y**2 - mu**2, w)

    # NOTE: The reason we compute the mean/variance of the
    # the current window is that we need something to compare
    # the prediction to. There is no "zero" that we can run
    # a significance test against (i.e. H_0 is E[next] == 0),
    # we need it to be measured against the current estimate
    # of recent loss.

    # We want this to be positive if we should keep going
    diff = mu - p$fit
    diff.se = sqrt(p$se.fit + se)  # not at all right...
    #diff.se = sqrt(p$se.fit * p$se.fit + se * se)  # technically correct
    #diff.se = p$se.fit # closer to what you want
    t = diff / diff.se
    if (plot.t)
      points(c(length(y)), t, col="green")
    if (debug) {
      print(m)
      print(p)
      print(paste("diff", diff, "diff.se", diff.se))
      print(paste("t", t, "t.star", t.star))
    }
    return((t < t.star))
  }
}

# Plots and executes stopping strategy
show.stop = function(x, y, noise, alpha, inf.window=T) {
  y.noisy = y + noise
  par(mfrow=c(2, 1))

  # Plot for iterates
  plot(x, y, ylim=c(min(y.noisy), max(y.noisy)), type="l")
  points(x, y.noisy, col="blue")

  # size of history
  k = 60
  inf.window.decay = 10 * k

  # Plot for stopping significance
  # 2-sample t-test
  t.star = qt(1 - alpha, k - 1)  # significant @ alpha if t > t.star
  plot(c(), c(), xlim=c(1, length(x)), ylim=c(-2, 4))
  abline(h=t.star, col="red")

  # off is where to pretend we're doing the stop check
  for (off in 30:length(x)) {
    if (stop(y.noisy[1:off], alpha, inf.window, plot.t=T))
      abline(v=off)
  }
}


show.many.stops = function() {
  alpha = 0.5

  ### CURVES THAT POP BACK UP ###
  x = 1:200
  y = 1000 + -0.5 * x + 1.5 * (x - 75) ** 2
  noise = rnorm(length(y), 0, 1000)
  r(x, y, noise, alpha)

  ### CURVES THAT DROP OFF FORVEVER ###
  x = 1:200
  y = 20 * exp(-x / 30)
  noise = rnorm(length(x), 0, 1)
  r(x, y, noise, alpha)

  x = 1:200
  y = 20 * exp(-x / 30)
  noise = rnorm(length(x), 0, 2)
  r(x, y, noise, alpha)

  x = 1:200
  y = 100 * exp(-x / 30)
  noise = rnorm(length(x), 0, 25)
  r(x, y, noise, alpha)

  ### CURVES THAT HAVE SIN IN THEM ###
  x = 1:200
  y = 1000 + -0.5 * x + 1.5 * (x - 75) ** 2
  y = y + 1000 * sin(x / 5)
  noise = rnorm(length(y), 0, 1000)
  r(x, y, noise, alpha)

  ### REAL CURVES ###
  y = read.table("/tmp/v", header=F)[,1]
  x = 1:length(y)
  noise = rep(0, length(y))
  r(x, y, noise, alpha)

  y = read.table("/tmp/v", header=F)[,1]
  x = 1:length(y)
  noise = rnorm(length(y), 0, 0.2)
  r(x, y, noise, alpha)
}


# Takes three arguments:
# 1) A file of dev set losses, one per line. Shouldn't be scores, want to *minimize* loss.
# 2) A value between 0 and 1, small values will be agressive in stopping quickly
#    and large values will stop only if performance seems to be going up.
#    0.25 is a nice default.
# 3) The width of a Gaussian kernel concerning which scores are relevant: I would
#    leave this at 50 unless you are really having problems.
args = commandArgs(trailing=T)
stopifnot(length(args) == 3)
y.file = args[1]
alpha = as.numeric(args[2])
k = as.numeric(args[3])
y = read.table(y.file, header=F)[,1]

if (stop(y, alpha, k)) {
  write("stop", stdout())
} else {
  write("continue", stdout())
}








