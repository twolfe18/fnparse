
% budget is measured in bits of entropy (thrid column in input file)
% output will contain the line indices (1-indexed) of the features to keep
function knapsack_feature_set(mi_hx_input_file, feature_set_output_file, budget)

  % awk '{print NR, $1, 10*rand()}' <framenet-ig-feat.txt >/tmp/t
  %vw = dlmread('/tmp/t');
  vw = dlmread(mi_hx_input_file);

  values = vw(:,1); v = values;
  weights = vw(:,2); w = weights;

  % 100 buckets == 2 decimal places
  Nv = 100;
  Nw = 100;
  v = normalize(v, Nv);
  w = normalize(w, Nw) + 1;

  % TODO select the indices where v/w > threshold
  heuristic = -values ./ weights;
  [heuristic_sorted, ord] = sort(heuristic);
  rel = ord(1:min(1000, length(v)));

  budget_alg = round(budget * (mean(w) / mean(weights)));
  [best keep_small] = knapsack(w(rel), v(rel), budget_alg);
  keep = rel(find(keep_small));

  disp(['mean(w)=', num2str(mean(w)), ' mean(weights)=', num2str(mean(weights))])
  disp(['budget=', num2str(budget), ' budget_alg=', num2str(budget_alg)])
  disp(['objective=', num2str(best)])
  disp(['num_kept=', num2str(size(keep_small))])

  % Show the chosen items (solution)
  %[keep, v(keep), w(keep)]
  %best == sum(v(keep))
  f = fopen(feature_set_output_file, 'w');
  for index = keep
    fprintf(f, '%d\n', index);
  end
  fclose(f);
end;

function [norm] = normalize(continuous_values, num_buckets)
  norm = continuous_values;
  norm = norm - min(norm);
  norm = norm / max(norm);
  norm = round(norm * num_buckets);
end;

%knapsack_feature_set('/tmp/t', '/tmp/b50', 50);

