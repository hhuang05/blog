package blog.engine.pbvi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Stack;

import blog.Main;
import blog.TemporalQueriesInstantiator;
import blog.engine.experiments.query_parser;
import blog.engine.onlinePF.PFEngine.PFEngineSampled;
import blog.engine.onlinePF.evidenceGenerator.EvidenceQueryDecisionGeneratorOnline;
import blog.engine.onlinePF.inverseBucket.TimedParticle;
import blog.model.ArgSpec;
import blog.model.BuiltInTypes;
import blog.model.DecisionEvidenceStatement;
import blog.model.DecisionFunction;
import blog.model.Evidence;
import blog.model.FuncAppTerm;
import blog.model.Function;
import blog.model.Model;
import blog.model.Query;
import blog.model.Term;
import blog.model.TrueFormula;
import blog.model.Type;
import blog.world.AbstractPartialWorld;
import blog.world.PartialWorld;
import blog.bn.BasicVar;
import blog.bn.BayesNetVar;
import blog.common.Util;

public class OUPBVI {
	private Model model;
	private List<Query> queries;
	private Properties properties;
	private int horizon;
	private int numBeliefs;
	private boolean usePerseus = true;
	
	private Set<State> alphaKeys;
	
	private TemporalQueriesInstantiator setQI;
	
	public OUPBVI(Model model, Properties properties, List<Query> queries, List<String> queryStrings, int horizon, int numBeliefs) {
		this.model = model;
		this.horizon = horizon;
		this.properties = properties;
		this.queries = queries;
		this.numBeliefs = numBeliefs;
		alphaKeys = new HashSet<State>();
		System.out.println("Queries " + queries);
		
		for (Type typ: (List<Type>) model.getObsTyp()){
			queryStrings.add("Number_"+typ+"(t)");
		}
		
		setQI = new TemporalQueriesInstantiator(model, EvidenceQueryDecisionGeneratorOnline.makeQueryTemplates(queryStrings));	
	}
	
	public Collection<Query> getQueries(int t) {
		Collection<Query> newQs = setQI.getQueries(t);
		for (Query q : newQs) {
			q.compile();
		}
		newQs.addAll(this.queries);
		return newQs;
	}
	
	public Set<FiniteStatePolicy> run() {
		Set<Belief> beliefs = new HashSet<Belief>();
		PFEngineSampled initPF = new PFEngineSampled(model, properties);
		initPF.answer(getQueries(0));
		initPF.afterAnsweringQueries2();
		
		//beliefs.add(new Belief(initBelief, this));
		SampledPOMDP pomdp = new SampledPOMDP(this);
		Belief initBelief = addToSampledPOMDP(new Belief(initPF, this), pomdp);
		System.out.println(initBelief);
		beliefs.add(initBelief);
		
		Set<Belief> newBeliefs = new HashSet<Belief>(); //beliefs;
		newBeliefs.addAll(beliefs);
		
		int numIter = 0;
		while (newBeliefs.size() < numBeliefs) {
			//newBeliefs.addAll(beliefExpansionDepthFirst(
			//		new Belief(initBelief.copy(), this), pomdp));
			newBeliefs = maxNormBeliefExpansion(newBeliefs, pomdp);
			System.out.println("run.expansion.iteration: " + numIter);
			System.out.println("run.expansion.newsize: " + newBeliefs.size());
			numIter++;
		}
		beliefs = newBeliefs;
		
		for (Belief b : beliefs) {
			System.out.println("Belief: " + b);
		}
		alphaKeys.addAll(pomdp.getStates());
		System.out.println("run.expansion.beliefsize: " + beliefs.size());
		
		Set<FiniteStatePolicy> policies = new HashSet<FiniteStatePolicy>();
		for (int t = horizon - 1; t >= 0; t--) {
			Set<FiniteStatePolicy> newPolicies = singleBackup(policies, beliefs, pomdp, t);
			setAlphaVectors(newPolicies, policies, t, pomdp);
			policies = newPolicies;
			
			System.out.println("run policies " + policies.size() + " " + t);
			int i = 0;
			for (FiniteStatePolicy p : policies) {
				System.out.println(p.toDotString("p" + "_t" + t + "_i" + i));
				i++;
			}
		}
		
		double bestVal = Double.NEGATIVE_INFINITY;
		FiniteStatePolicy bestPolicy = null;
		for (FiniteStatePolicy p : policies) {
			double val = evalPolicy(initBelief, p);
			if (val > bestVal) {
				bestVal = val;
				bestPolicy = p;
			}
		}
		System.out.println("Best policy for initial belief: " + bestPolicy.toDotString("p0"));
		System.out.println("Best value: " + bestVal);
		System.out.println("Best value previously computed: " + prevBestVals.get(initBelief));
		System.out.println("Init belief: " + initBelief);
		System.out.println();
		System.out.println("run num beliefs " + beliefs.size());
		System.out.println("run num states " + pomdp.getStates().size());
		System.out.println("Eval Policy Times " + evalPolicyTimes);
		System.out.println("Eval Policy Counts " + evalPolicyCounts);
		System.out.println("Eval State Count " + evalStateCount);
		return policies;
	}
	
	private Set<FiniteStatePolicy> createMergedPolicySet(Set<FiniteStatePolicy> policies, FiniteStatePolicy policy) {
		Set<FiniteStatePolicy> newPolicies = new HashSet<FiniteStatePolicy>();
		//System.out.println("merge new policy " + policy);
		for (FiniteStatePolicy p : policies) {
			if (!policy.merge(p)) {
				newPolicies.add(p);
			} else {
			//	System.out.println("merged " + p);
			}
		}
		//System.out.println("adding policy " + policy);
		newPolicies.add(policy);
		return newPolicies;
	}
	
	private Belief getSingletonBelief(State state) {
		return getSingletonBelief(state, Integer.parseInt((String) properties.get("numParticles")));
	}

	private Belief getSingletonBelief(State state, int numParticles) {
		Properties properties = (Properties) this.properties.clone();
		properties.setProperty("numParticles", "" + numParticles);
		PFEngineSampled pf = new PFEngineSampled(model, properties, state.getWorld(), state.getTimestep());
		Belief b = new Belief(pf, this);
		return b;
	}

	
	private Set<Evidence> getActions(Belief b) {
		State s = (State) Util.getFirst(b.getStates());
		PartialWorld w = s.getWorld();
		int timestep = b.getTimestep();
		Map<BayesNetVar, BayesNetVar> observableMap = ((AbstractPartialWorld) w).getObservableMap();
	
		Map<Type, Set<BayesNetVar>> observedVarsByType = partitionVarsByType(observableMap, w);
		List<DecisionFunction> decisionFunctions = model.getDecisionFunctions();

		Set<Evidence> actions = new HashSet<Evidence>();
		for (DecisionFunction f : decisionFunctions) {
			Set<List<Term>> argLists = enumArgListsForFunc(f, observedVarsByType);
			for (List<Term> argList : argLists) {
				Evidence action = new Evidence();
				List<ArgSpec> argTerms = new ArrayList<ArgSpec>();
				for (Term term : argList) {
					argTerms.add(term);
				}
				argTerms.add(
						BuiltInTypes.TIMESTEP.getCanonicalTerm(
								BuiltInTypes.TIMESTEP.getGuaranteedObject(timestep)));
				FuncAppTerm left = new FuncAppTerm(f, argTerms);
				DecisionEvidenceStatement decisionStatement = new DecisionEvidenceStatement(left, TrueFormula.TRUE);
				action.addDecisionEvidence(decisionStatement);
				action.compile();
				actions.add(action);
			}
			
		}
		return actions;
		
	}
	
	private Set<List<Term>> enumArgListsForFunc(DecisionFunction f,
			Map<Type, Set<BayesNetVar>> observedVarsByType) {
		return enumArgListsForFunc(f, observedVarsByType, 0);
		
	}

	private Set<List<Term>> enumArgListsForFunc(DecisionFunction f,
			Map<Type, Set<BayesNetVar>> observedVarsByType,
			int argNum) {
		Type[] argTypes = f.getArgTypes();
		Set<List<Term>> result = new HashSet<List<Term>>();
		if (argNum == argTypes.length) {
			result.add(new ArrayList<Term>());
			return result;
		}
		Set<List<Term>> restOfArgs = enumArgListsForFunc(f, observedVarsByType, argNum + 1);
		Type argType = argTypes[argNum];
		Set<Term> terms = new HashSet<Term>();
		Set<BayesNetVar> observedVars = observedVarsByType.get(argType);
		if (observedVarsByType.get(argType) != null) {
			for (BayesNetVar v : observedVars) {
				terms.add(((BasicVar) v).getCanonicalTerm());
			}
		}
		Collection guaranteed = argType.getGuaranteedObjects();
		if (guaranteed != null) {
			for (Object g : guaranteed) {
				terms.add(argType.getCanonicalTerm(g));
			}
		}
		if (argType.equals(Type.getType("Timestep"))) {
			return restOfArgs;
		} 
		for (Term term : terms) {
			for (List<Term> rest : restOfArgs) {
				List<Term> newList = new ArrayList<Term>();
				newList.add(term);
				newList.addAll(rest);
				result.add(newList);
			}
		}
		return result;	
	}

	private Map<Type, Set<BayesNetVar>> partitionVarsByType(
			Map<BayesNetVar, BayesNetVar> observabilityMap, PartialWorld w) {
		Map<Type, Set<BayesNetVar>> result = new HashMap<Type, Set<BayesNetVar>>();
		for (BayesNetVar var : observabilityMap.keySet()) {
			Type type = ((BasicVar) var).getType();
			if (!result.containsKey(type))
				result.put(type, new HashSet<BayesNetVar>());
			result.get(type).add(var);
		}
		return result;
	}


	private Set<Belief> beliefExpansion(Set<Belief> beliefs) {
		Set<Belief> newBeliefs = new HashSet<Belief>();
		for (Belief b : beliefs) {
			Set<Evidence> actions = getActions(b);
			for (Evidence action : actions) {
				Belief next = b.sampleNextBelief(action);
				newBeliefs.add(next);
			}
			b.setParticleFilter(null);
		}
		return newBeliefs;
	}
	
	private Set<Belief> beliefExpansionDepthFirst(Belief b, SampledPOMDP pomdp) {
		Set<Belief> newBeliefs = new HashSet<Belief>();
		Belief next;
		for (int i = 0; i < horizon; i++) {
			Set<Evidence> actions = getActions(b);
			Evidence action = pickRandomObs(actions);
			next = b.sampleNextBelief(action);
			newBeliefs.add(next);
			b.setParticleFilter(null);
			b = next;
			addToSampledPOMDP(b, pomdp);
		}
		b.setParticleFilter(null);
		return newBeliefs;
	}
	
	private Set<Belief> maxNormBeliefExpansion(Set<Belief> beliefs, SampledPOMDP pomdp) {
		Set<Belief> newBeliefs = new HashSet<Belief>(beliefs);
		for (Belief belief : beliefs) {
			if (belief.getTimestep() == horizon) continue;
			if (newBeliefs.size() >= numBeliefs) break;
			Set<Evidence> actions = pomdp.getActions(belief);
			Belief bestBelief = null;
			int maxDiff = 0;
			for (Evidence action : actions) {
				int minDiff = Integer.MAX_VALUE;
				Belief next = belief.sampleNextBelief(action);
				if (next.ended()) continue;
				if (bestBelief == null) bestBelief = next;
				for (Belief other : newBeliefs) {
					if (other.getTimestep() != next.getTimestep()) continue;
					int diff = next.diffNorm(other);
					if (minDiff > diff) {
						minDiff = diff;
					}
				}
				if (maxDiff < minDiff) {
					bestBelief = next;
					maxDiff = minDiff;
				}
			}
			newBeliefs.add(bestBelief);
			addToSampledPOMDP(bestBelief, pomdp);
			System.out.println("max diff: " + maxDiff);
		}
		return newBeliefs;
	}
	
	private double evalPolicy(Belief b, FiniteStatePolicy p) {
		Double value = 0D;
		int totalCount = 0;
		for (State s : b.getStates()) {
			value += evalPolicy(s, p) * b.getCount(s);
			totalCount += b.getCount(s);
		}
		return value/totalCount;
	}
	
	int evalStateCount = 0;
	private double evalPolicy(State s, FiniteStatePolicy p) {
		Double v = p.getAlphaVector().getValue(s);
		if (v != null) {
			return v;
		}
		evalStateCount++;
		return evalPolicyDFS(getSingletonBelief(s, 1), p);
	}
	
	Map<Integer, Long> evalPolicyTimes = new HashMap<Integer, Long>();
	Map<Integer, Integer> evalPolicyCounts= new HashMap<Integer, Integer>();
	private double evalPolicyDFS(Belief b, FiniteStatePolicy p) {
		if (b.ended()) return 0;
		
		Double v = p.getAlphaVector().getValue(b);
		if (v != null) {
			return v;
		}
		
		/*if (p.getAlphaVector().getSize() == alphaKeys.size()) {
			System.out.println("alpha vector missing states in belief");
			System.out.println(b);
			System.out.println(p.getAlphaVector());
			//System.exit(0);
		}*/
		
		Function valueFunc = (Function) model.getRandomFunc("value", 1);
		v = 0D;
		
		Double initialValue = 0D;
		int startingTimestep = b.getTimestep();
		Object initialTime = Type.getType("Timestep").getGuaranteedObject(startingTimestep);
		for (TimedParticle particle : b.getParticleFilter().particles) {
			Number value = (Number) valueFunc.getValueSingleArg(initialTime, particle.curWorld);
			initialValue += value.doubleValue();
		}
		initialValue /= b.getParticleFilter().particles.size();
		int numTrials = 100;
		for (int i = 0; i < numTrials; i++) {
			Belief curBelief = b;
			FiniteStatePolicy curPolicy = p;
			boolean alphaFound = false;
			double nextValue = 0; //set to nonzero if alpha vector can calc value of belief
			int stepsLeft = horizon;
			while (curPolicy != null) {//curBelief.getTimestep() < horizon) {
				if (curBelief.ended()) break;
				Double alphaValue = curPolicy.getAlphaVector().getValue(curBelief);
				if (alphaValue != null) {
					nextValue = alphaValue;
					alphaFound = true;
					break;
				}
				Evidence nextAction = curPolicy.getAction();
				curBelief = curBelief.sampleNextBelief(nextAction);		
				Evidence nextObs = curBelief.getLatestEvidence();
				FiniteStatePolicy nextPolicy = curPolicy.getNextPolicy(nextObs);
				if (nextPolicy == null && !curBelief.ended()) { 
					nextPolicy = curPolicy.getApplicableNextPolicy(nextObs, curBelief);
					if (nextPolicy != null) {
						curPolicy.setNextPolicy(nextObs, nextPolicy);
						curPolicy.addObsNote(nextObs, "random applicable");
					}
				}
				curPolicy = nextPolicy;
				stepsLeft--;
			}
			
			Object timestep = Type.getType("Timestep").getGuaranteedObject(curBelief.getTimestep());
			double curValue = 0D;
			List<TimedParticle> particles = curBelief.getParticleFilter().particles;
			for (TimedParticle particle : particles) {	
				Number value = (Number) valueFunc.getValueSingleArg(timestep, particle.curWorld);
				curValue += value.doubleValue();
			}
			if (usePerseus && !alphaFound && !curBelief.ended()) {
				v += stepsLeft * -5 * particles.size();
			} else if (usePerseus && alphaFound && !curBelief.ended()) {
				v += (horizon - stepsLeft) * 5 * particles.size(); //not using horizon - stepsLeft from the policy found
			}

			v += nextValue + curValue/particles.size() - initialValue;
		}
		
		/*
		if (!evalPolicyTimes.containsKey(startingTimestep)) {
			evalPolicyTimes.put(startingTimestep, 0L);
			evalPolicyCounts.put(startingTimestep, 0);
		}
		evalPolicyTimes.put(startingTimestep, evalPolicyTimes.get(startingTimestep) + (System.currentTimeMillis() - startTime));
		evalPolicyCounts.put(startingTimestep, evalPolicyCounts.get(startingTimestep) + 1);
		*/
		return v/numTrials;
	}
	
	private Evidence pickRandomObs(Set<Evidence> evidences) {
		int rand = (int)(Math.random() * evidences.size());
		int i = 0;
		for (Evidence e : evidences) {
			if (i == rand) return e;
			i++;
		}
		return null;
	}
	
	private Belief addToSampledPOMDP(Belief belief, SampledPOMDP pomdp) {
		Stack<State> toExpand = new Stack<State>();
		Set<State> expanded = new HashSet<State>();
		Set<State> initStates = new HashSet<State>();
		initStates.addAll(belief.getStates());	
		//initStates.removeAll(pomdp.getStates());
		toExpand.addAll(initStates);
		
		while (!toExpand.isEmpty()) {
			State s = toExpand.pop();
			if (expanded.contains(s)) continue;
			if (pomdp.getActions(s) != null) continue;
			pomdp.addState(s);
			s = pomdp.getState(s);
			expanded.add(s);
			Belief b = getSingletonBelief(s, 1);
			Set<Evidence> actions = getActions(b);
			
			pomdp.addStateActions(s, actions);
	
			for (Evidence a : actions) {
				//ActionPropagated ap = b.beliefsAfterAction(a);
				/*Set<Evidence> observations = ap.getObservations();
				for (Evidence o : observations) {
					Belief next = ap.getNextBelief(o, this);
					next.setParticleFilter(null);
					Set<State> nextStates = new HashSet<State>(next.getStates());
					pomdp.addStates(nextStates);
					
					int count = (int) ap.getObservationCount(o);
					pomdp.addObsWeights(s, a, o, count);
					pomdp.addNextBelief(s, a, o, next);

					if (next.getTimestep() < horizon) {
						nextStates.removeAll(expanded);
						nextStates.removeAll(pomdp.getPropagatedStates());
						nextStates.removeAll(toExpand);
						//toExpand.addAll(nextStates);
					}
				}*/
				pomdp.addReward(s, a, b.getReward(a));
			}
			System.out.println("# states left to expand " + toExpand.size());
			System.out.println("# states expanded " + expanded.size());
		}
		System.out.println("# states " + pomdp.getStates().size());
		System.out.println("# states expanded " + expanded.size());
		
		Belief newBelief = new Belief(this);
		for (State s : belief.getStates()) {
			newBelief.addState(pomdp.getState(s), belief.getCount(s));
		}
		return newBelief;
	}
	
	private Map<Belief, Double> prevBestVals = new HashMap<Belief, Double>();
	private Map<Belief, FiniteStatePolicy> prevBestPolicies = 
			new HashMap<Belief, FiniteStatePolicy>();
	
	private Set<FiniteStatePolicy> singleBackup(Set<FiniteStatePolicy> oldPolicies, 
			Set<Belief> beliefs, 
			SampledPOMDP pomdp,
			int t) {
		Set<FiniteStatePolicy> newPolicies = new HashSet<FiniteStatePolicy>();
		List<Belief> shuffledBeliefs = new ArrayList<Belief>(beliefs);
		Collections.shuffle(shuffledBeliefs);
		for (Belief b : shuffledBeliefs) {
			if (usePerseus) {
				boolean foundBetterPolicy = false;
				for (FiniteStatePolicy newPolicy : newPolicies) {
					double v = evalPolicy(b, newPolicy);
					Double bestPrevVal = prevBestVals.get(b);
					if (bestPrevVal == null) bestPrevVal = -5.0 * horizon;
					if (v > bestPrevVal) {
						prevBestVals.put(b, v);
						prevBestPolicies.put(b, newPolicy);
						foundBetterPolicy = true;
						break;
					}
				}
				if (foundBetterPolicy) {
					System.out.println("Skipping belief");
					continue;
				}
			}
			//if (b.getTimestep() != t) continue;
			FiniteStatePolicy newPolicy = singleBackupForBelief(oldPolicies, b, pomdp, t);
			if (usePerseus) {
				setAlphaVector(newPolicy, pomdp);
				//newPolicy.setAlphaVector(new AlphaVector());
				double newVal = evalPolicy(b, newPolicy);
				if (!prevBestVals.containsKey(b) || prevBestVals.get(b) < newVal) {
					prevBestVals.put(b, newVal);
					prevBestPolicies.put(b, newPolicy);
				} else {
					newPolicies.add(prevBestPolicies.get(b));
				}
			}
			
			newPolicies = createMergedPolicySet(newPolicies, newPolicy);
			System.out.println("SingleBackup numbeliefs: " + beliefs.size());
			
		}
		/* no longer need old alpha vectors? They might be still useful
		for (FiniteStatePolicy p : oldPolicies) {
			p.setAlphaVector(null);
		}*/
		Timer.print();
		return newPolicies;
	}
	
	private FiniteStatePolicy singleBackupForBelief(
			Set<FiniteStatePolicy> oldPolicies,
			Belief b,
			SampledPOMDP pomdp,
			int t) {
		long startTime = System.currentTimeMillis();
		
		Map<Evidence, FiniteStatePolicy> bestPolicyMap = new HashMap<Evidence, FiniteStatePolicy>();
		Evidence bestAction = null;
		
		Set<Evidence> actions = pomdp.getActions(b);
		System.out.println("single backup actions: " + actions);
		//System.out.println("single backup belief: " + b);
		System.out.println("single backup num oldpolicies: " + oldPolicies.size());
		Double bestValue = null;
		for (Evidence action : actions) {
			double value = 0;
			double totalWeight = 0;
			boolean ended = false;
			Map<Evidence, FiniteStatePolicy> policyMap = new HashMap<Evidence, FiniteStatePolicy>();
			//Map<Evidence, Integer> observations = pomdp.getObservations(b, action);
			if (t < horizon - 1) {
				ActionPropagated ap = b.beliefsAfterAction(action);
				for (Evidence obs : ap.getObservations()) {
					Belief next = ap.getNextBelief(obs, this);//pomdp.getNextBelief(b, action, obs);
					if (next.ended()) {
						ended = true;
						break;
					}
					Double bestContingentValue = Double.NEGATIVE_INFINITY;
					FiniteStatePolicy bestContingentPolicy = null;
					for (FiniteStatePolicy p : oldPolicies) {
						if (!p.isApplicable(next)) continue;
						//System.out.println("action before eval contingent policy: " + action);
						Double v = evalPolicy(next, p);
						//System.out.println("value: " + v);
						//System.out.println("next action: " + p.getAction());
						if (v > bestContingentValue) {
							bestContingentValue = v;
							bestContingentPolicy = p;
						}
					}	
					double weight = ap.getObservationCount(obs); //observations.get(obs);
					policyMap.put(obs, bestContingentPolicy);
					/*System.out.println("cont policy select obs " + obs);
					System.out.println("cont policy select value for belief " + bestContingentValue + " weight " + weight);
					System.out.println("cur action" + action);
					/*System.out.println("next policy: " + bestContingentPolicy);
					System.out.println("belief " + b);
					System.out.println("next " + next);*/

					value += bestContingentValue * weight;
					totalWeight += weight;
				}
				if (totalWeight > 0)
					value = value/totalWeight;
			} else if (usePerseus){
				value = -5.0 * horizon;
			}
			double reward = pomdp.getAvgReward(b, action);
			if (usePerseus) {
				value += 5.0;
				if (ended) {
					value += (horizon - t - 1) * 5;
				}
			}
			System.out.println("backup reward for action " + reward + " " + action + "value " + value + " timestep: " + t);
			value += reward;
			//System.out.println("value for belief and policy map: " + value + " " + policyMap);
			if (bestValue == null || value > bestValue) {
				bestValue = value;
				bestPolicyMap = policyMap;
				bestAction = action;
			}
		}
			
		
		System.out.println("bestAction: " + bestAction + " " + bestValue + " timestep: " + t);
		FiniteStatePolicy newPolicy = new FiniteStatePolicy(bestAction, bestPolicyMap);
		//System.out.println("new policy" + newPolicy);
		System.out.println("singlebackupforbelief.time " + (System.currentTimeMillis() - startTime));
		return newPolicy;		
	}
	
	private void setAlphaVector(
			FiniteStatePolicy policy,
			SampledPOMDP pomdp) {
		if (policy.getAlphaVector() == null) {
			policy.setAlphaVector(new AlphaVector());
		}
		Set<State> states = pomdp.getStates();
		for (State state : states) {
			if (policy.getAlphaVector().getValue(state) == null)
				policy.getAlphaVector().setValue(state, evalPolicy(state, policy));
		}
	}
	
	private void setAlphaVectors(
			Set<FiniteStatePolicy> policies,
			Set<FiniteStatePolicy> oldPolicies,
			int timestep,
			SampledPOMDP pomdp) {
		System.out.println("Start alphavector calculations");
		
		Set<State> states = pomdp.getStates();
		
		for (FiniteStatePolicy policy : policies) {
			if (policy.getAlphaVector() == null)
				policy.setAlphaVector(new AlphaVector());
		}

		System.out.println("singleBackup.states.size " + states.size());
		System.out.println("oldPolicies.size " + oldPolicies.size());
		System.out.println("policies.size " + policies.size());
		long now = System.currentTimeMillis();
		int numStatesUpdated = 0;
		for (State state : states) {
			System.out.println("Computing alpha values for state " + state);
			//if (state.getTimestep() != timestep) continue;
			numStatesUpdated++;
			for (FiniteStatePolicy policy : policies) {
				double val = evalPolicy(state, policy);
				if (policy.getAlphaVector().getValue(state) == null)
					policy.getAlphaVector().setValue(state, val);
			}
		}

		System.out.println("backup per state took on avg " 
				+ (System.currentTimeMillis() - now)/numStatesUpdated
				+ "ms");
	}
	
	public static OUPBVI makeOUPBVI(List modelFilePath, 
			String queryFile,
			int numParticles,
			int horizon,
			int numBeliefs) {
		query_parser file = new query_parser(queryFile);
		Collection linkStrings = Util.list();
		List<String> queryStrings = file.queries;
		System.out.println(queryStrings);
		Model model = new Model();
		Evidence evidence = new Evidence();
		LinkedList queries = new LinkedList();
		Main.simpleSetupFromFiles(model, evidence, queries,
				modelFilePath);
		Properties properties = new Properties();
		properties.setProperty("numParticles", "" + numParticles);
		properties.setProperty("useDecayedMCMC", "false");
		properties.setProperty("numMoves", "1");
		
		Util.setVerbose(false);
		return new OUPBVI(model, properties, queries, queryStrings, horizon, numBeliefs);
	}
	
	public static void main(String[] args) {
		System.out.println("Usage: modelFile queryFile numParticles horizon numBeliefs (seed)");
		List<String> modelFiles = new ArrayList<String>();
		modelFiles.add(args[0]);
		long now = System.currentTimeMillis();

		if (args.length > 6) {
			Util.initRandom(Long.parseLong(args[6]));
		} else {
			Util.initRandom(true);
		}
		OUPBVI oupbvi = makeOUPBVI(modelFiles, 
				args[1], 
				Integer.parseInt(args[2]), 
				Integer.parseInt(args[3]),
				Integer.parseInt(args[4]));
		if (args.length > 5) {
			oupbvi.setUsePerseus(Boolean.parseBoolean(args[5]));
		} 
		Set<FiniteStatePolicy> policies = oupbvi.run();
		/*for (FiniteStatePolicy p : policies) {
			System.out.println(p.toDotString("p0"));
		}*/
		System.out.println("Running time: " + (System.currentTimeMillis() - now) + "ms");
		Belief.printTimingStats();
	}

	private void setUsePerseus(boolean usePerseus) {
		this.usePerseus = usePerseus;
	}

	public Model getModel() {
		return model;
	}

	public Properties getProperties() {
		return properties;
	}
}