package edu.jhu.hlt.fnparse.inference;

/**
 ******************** Factor graph model that is similar to SEMAFOR ********************
 * 
 * For a sentence with length n, having s spans:
 * 
 * One "target" variable per word in a sentence which describes the frame that
 * is evoked by that target word:
 *   [f_1, f_2, ... f_n]
 * 
 * If the frame which with the most roles has k roles, then we will
 * have k * n "role" variables which describe what span is the realization
 * of this frame (given by the target) and role:
 *   r_{i,j} \in [1, 2, ... s] \forall
 *     i \in [1, 2, ... n]	# word index
 *     j \in [1, 2, ... k]	# role
 * 
 * There will be unary factors on each f_i variable which is comparable
 * to the target identification log-linear model in SEMAFOR.
 * 
 * There will be binary factors that connect each frame target with each of its role variables:
 *   for i in range(n):
 *     for j in range(k):
 *       yield Factor(f_i, r_{i,j})
 * 
 * We will do decoding by starting with sum-product BP, and slowly anneal towards
 * max-product BP (viterbi).
 * 
 * If we want to train (relatively) quickly, as they did with SEMAFOR, we will
 * mimic piecewise training by first learning the target unary factor weights
 * by _not adding the role variables_, and doing maximum conditional likelihood training.
 * When it is time to train the target-role binary factors, we will clamp the target
 * variables at the predicted values, and proceed with maximum conditional likelihood training.
 * 
 * Concerning the constraint that two roles for a given target may not map to the same span,
 * we can first ignore this during training, and do decoding using beam search as in SEMAFOR.
 * A slower way to do this is to add a MutexFactor(r_{i,j}, r_{i,k}) \forall j < k.
 * 
 * Another issue to address is how to do span identification.
 * -> can prune spans in a variety of ways (NER tagger, constituents of parse, up to a certain width, etc).
 *    perhaps these should, in the exact case, be unary factors on target-role variables (factor only considers the span)
 * -> can choose to parameterize a span as (headIdx, leftExpand, rightExpand).
 *    this may mean that we can train more robust (target, role-head) factors/features
 */
public class SemaforicFactorGraph {

}


