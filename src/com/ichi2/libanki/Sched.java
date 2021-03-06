/****************************************************************************************
 * Copyright (c) 2011 Norbert Nagold <norbert.nagold@gmail.com>                         *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General private License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General private License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General private License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.libanki;

import com.ichi2.anki2.R;

import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteConstraintException;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.Pair;

import com.ichi2.anki.AnkiDroidApp;
import com.ichi2.async.DeckTask.TaskData;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

public class Sched {

	// whether new cards should be mixed with reviews, or shown first or last
	public static final int NEW_CARDS_DISTRIBUTE = 0;
	public static final int NEW_CARDS_LAST = 1;
	public static final int NEW_CARDS_FIRST = 2;

	// new card insertion order
	public static final int NEW_CARDS_RANDOM = 0;
	public static final int NEW_CARDS_DUE = 1;

	// review card sort order
	public static final int REV_CARDS_RANDOM = 0;
	public static final int REV_CARDS_OLD_FIRST = 1;
	public static final int REV_CARDS_NEW_FIRST = 2;

	// removal types
	public static final int REM_CARD = 0;
	public static final int REM_NOTE = 1;
	public static final int REM_DECK = 2;

	// count display
	public static final int COUNT_ANSWERED = 0;
	public static final int COUNT_REMAINING = 1;

	// media log
	public static final int MEDIA_ADD = 0;
	public static final int MEDIA_REM = 1;

	// dynamic deck order
	public static final int DYN_OLDEST = 0;
	public static final int DYN_RANDOM = 1;
	public static final int DYN_SMALLINT = 2;
	public static final int DYN_BIGINT = 3;
	public static final int DYN_LAPSES = 4;
	public static final int DYN_FAILED = 5;
	public static final int DYN_ADDED = 6;
	public static final int DYN_DUE = 7;

	// model types
	public static final int MODEL_STD = 0;
	public static final int MODEL_CLOZE = 1;

	private static final String[] REV_ORDER_STRINGS = { "ivl DESC", "ivl" };
	private static final int[] FACTOR_ADDITION_VALUES = { -150, 0, 150 };

	// not in libanki
	public static final int DECK_INFORMATION_NAMES = 0;
	public static final int DECK_INFORMATION_SIMPLE_COUNTS = 1;
	public static final int DECK_INFORMATION_EXTENDED_COUNTS = 2;	

	private Collection mCol;
	private String mName = "std";
	private int mQueueLimit;
	private int mReportLimit;
	private int mReps;
	private boolean mHaveQueues;
	private boolean mClearOverdue;
	private int mToday;
	public long mDayCutoff;

	private int mNewCount;
	private int mLrnCount;
	private int mRevCount;

	private int mNewCardModulus;

	private double[] mEtaCache = new double[]{-1, -1, -1, -1};

	// Queues
	private LinkedList<long[]> mNewQueue;
	private LinkedList<long[]> mLrnQueue;
	private LinkedList<long[]> mLrnDayQueue;
	private LinkedList<long[]> mRevQueue;

	private LinkedList<Long> mNewDids;
	private LinkedList<Long> mLrnDids;
	private LinkedList<Long> mRevDids;

	private TreeMap<Integer, Integer> mGroupConfs;
	private TreeMap<Integer, JSONObject> mConfCache;

	/** all due cards which are not in the current deck selection */
	private int mNonselectedDues;

	/**
	 * queue types: 0=new/cram, 1=lrn, 2=rev, 3=day lrn, -1=suspended, -2=buried
	 * revlog types: 0=lrn, 1=rev, 2=relrn, 3=cram positive intervals are in
	 * positive revlog intervals are in days (rev), negative in seconds (lrn)
	 */

	public Sched(Collection col) {
		mCol = col;
		mQueueLimit = 50;
		mReportLimit = 1000;
		mReps = 0;
		mHaveQueues = false;
		mClearOverdue = true;
		_updateCutoff();

		// Initialise queues
		mNewQueue = new LinkedList<long[]>();
		mLrnQueue = new LinkedList<long[]>();
		mLrnDayQueue = new LinkedList<long[]>();
		mRevQueue = new LinkedList<long[]>();
	}

	/**
	 * Pop the next card from the queue. None if finished.
	 */
	public Card getCard() {
		_checkDay();
		if (!mHaveQueues) {
			reset();
		}
		Card card = _getCard();
		if (card != null) {
			card.startTimer();
		}
		return card;
	}

	public void reset() {
		_updateCutoff();
		if (mClearOverdue) {
			removeFailed(true);
		}
		_resetLrn();
		_resetRev();
		_resetNew();
		mHaveQueues = true;
	}

	public boolean answerCard(Card card, int ease) {
		Log.i(AnkiDroidApp.TAG, "answerCard - ease:" + ease);
		boolean isLeech = false;
		mCol.markReview(card);
		mReps += 1;
		card.setReps(card.getReps() + 1);
		boolean wasNew = (card.getQueue() == 0);
		if (wasNew) {
			// came from the new queue, move to learning
			card.setQueue(1);
			// if it was a new card, it's now a learning card
			if (card.getType() == 0) {
				card.setType(1);
			}
			// init reps to graduation
			card.setLeft(_startingLeft(card));
			// dynamic?
			if (card.getODid() != 0 && card.getType() == 2) {
				// reviews get their ivl boosted on first sight
				card.setIvl(_dynIvlBoost(card));
				card.setODue(mToday + card.getIvl());
			}
			_updateStats(card, "new");
		}
		if (card.getQueue() == 1 || card.getQueue() == 3) {
			_answerLrnCard(card, ease);
			if (!wasNew) {
				_updateStats(card, "lrn");
			}
		} else if (card.getQueue() == 2) {
			isLeech = _answerRevCard(card, ease);
			_updateStats(card, "rev");
		} else {
			throw new RuntimeException("Invalid queue");
		}
		_updateStats(card, "time", card.timeTaken());
		card.setMod(Utils.intNow());
		card.setUsn(mCol.usn());
		card.flushSched();
		return isLeech;
	}

	public int[] counts() {
		return counts(null);
	}

	public int[] counts(Card card) {
		int[] counts = new int[3];
		counts[0] = mNewCount;
		counts[1] = mLrnCount;
		counts[2] = mRevCount;
		if (card != null) {
			int idx = countIdx(card);
			if (idx == 1) {
				counts[1] += card.getLeft() / 1000;
			} else {
				counts[idx] += 1;
			}
		}
		return counts;
	}

	/**
	 * Return counts over next DAYS. Includes today.
	 */
	public int dueForecast() {
		return dueForecast(7);
	}

	public int dueForecast(int days) {
		// TODO:...
		return 0;
	}

	public int countIdx(Card card) {
	    if (card.getQueue() == 3) {
	        return 1;
	    }
		return card.getQueue();
	}

	public int answerButtons(Card card) {
		if (card.getODid() == 0 && card.getODue() != 0) {
			JSONObject conf = _lapseConf(card);
			try {
				if (conf.getJSONArray("delays").length() > 1) {
					return 3;
				}
			} catch (JSONException e) {
				throw new RuntimeException(e);
			}
			return 2;
		}
		if (card.getQueue() == 2) {
			return 4;
		} else {
			return 3;
		}
	}

	/**
	 * Unbury cards when closing.
	 */
	public void onClose() {
		mCol.getDb().execute("UPDATE cards SET queue = type WHERE queue = -2");
	}

	// /**
	// * A very rough estimate of time to review.
	// */
	// public int eta() {
	// Cursor cur = null;
	// int cnt = 0;
	// int sum = 0;
	// try {
	// cur = mDb.getDatabase().rawQuery(
	// "SELECT count(), sum(taken) FROM (SELECT * FROM revlog " +
	// "ORDER BY time DESC LIMIT 10)", null);
	// if (cur.moveToFirst()) {
	// cnt = cur.getInt(0);
	// sum = cur.getInt(1);
	// }
	// } finally {
	// if (cur != null && !cur.isClosed()) {
	// cur.close();
	// }
	// }
	// if (cnt == 0) {
	// return 0;
	// }
	// double avg = sum / ((float) cnt);
	// int[] c = counts();
	// return (int) ((avg * c[0] * 3 + avg * c[1] * 3 + avg * c[2]) / 1000.0);
	// }

	/**
	 * Rev/lrn/time daily stats
	 * *************************************************
	 * **********************************************
	 */

	private void _updateStats(Card card, String type) {
		_updateStats(card, type, 1);
	}

	public void _updateStats(Card card, String type, int cnt) {
		String key = type + "Today";
		long did = card.getDid();
		ArrayList<JSONObject> list = mCol.getDecks().parents(did);
		list.add(mCol.getDecks().get(did));
		for (JSONObject g : list) {
			try {
				JSONArray a = g.getJSONArray(key);
				// add
				a.put(1, a.getInt(1) + cnt);
			} catch (JSONException e) {
				throw new RuntimeException(e);
			}
			mCol.getDecks().save(g);
		}
	}

	private void extendLimits(int newc, int rev) {
		JSONObject cur = mCol.getDecks().current();
		ArrayList<JSONObject> decks = new ArrayList<JSONObject>();
		decks.add(cur);
		try {
			decks.addAll(mCol.getDecks().parents(cur.getLong("id")));
			for (long did : mCol.getDecks().children(cur.getLong("id")).values()) {
				decks.add(mCol.getDecks().get(did));
			}
			for (JSONObject g : decks) {
				// add
				JSONArray ja = g.getJSONArray("newToday");
				ja.put(1, ja.getInt(1) - newc);
				g.put("newToday", ja);
				ja = g.getJSONArray("revToday");
				ja.put(1, ja.getInt(1) - rev);
				g.put("revToday", ja);
				mCol.getDecks().save(g);
			}
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	/** LIBANKI: not in libanki */
	public int eta(int[] counts) {
		return eta(counts, true);
	}
	public int eta(int[] counts, boolean reload) {
		double revYesRate;
		double revTime;
		double lrnYesRate;
		double lrnTime;
		if (reload || mEtaCache[0] == -1) {
			Cursor cur = null;
			try {
				cur = mCol.getDb().getDatabase().rawQuery("SELECT avg(CASE WHEN ease > 1 THEN 1 ELSE 0 END), avg(time) FROM revlog WHERE type = 1 AND id > " + ((mCol.getSched().getDayCutoff() - (7 * 86400)) * 1000), null);
				if (!cur.moveToFirst()) {
					return -1;
				}
				revYesRate = cur.getDouble(0);
				revTime = cur.getDouble(1);
				cur = mCol.getDb().getDatabase().rawQuery("SELECT avg(CASE WHEN ease = 3 THEN 1 ELSE 0 END), avg(time) FROM revlog WHERE type != 1 AND id > " + ((mCol.getSched().getDayCutoff() - (7 * 86400)) * 1000), null);
				if (!cur.moveToFirst()) {
					return -1;
				}
				lrnYesRate = cur.getDouble(0);
				lrnTime = cur.getDouble(1);
			} finally {
				if (cur != null && !cur.isClosed()) {
					cur.close();
				}
			}
			mEtaCache[0] = revYesRate;
			mEtaCache[1] = revTime;
			mEtaCache[2] = lrnYesRate;
			mEtaCache[3] = lrnTime;
		} else {
			revYesRate = mEtaCache[0];
			revTime = mEtaCache[1];
			lrnYesRate = mEtaCache[2];
			lrnTime = mEtaCache[3];			
		}
		// rev cards
		double eta = revTime * counts[2];
		// lrn cards
		double factor = Math.min(1/(1 - lrnYesRate), 10);
		double lrnAnswers = (counts[0] + counts[1] + counts[2] * (1 - revYesRate)) * factor;
		eta += lrnAnswers * lrnTime;
		return (int) (eta / 60000);
	}

	private int _walkingCount() {
		return _walkingCount(null, null, null);
	}

	private int _walkingCount(LinkedList<Long> dids) {
		return _walkingCount(dids, null, null);
	}

	private int _walkingCount(Method limFn, Method cntFn) {
		return _walkingCount(null, limFn, cntFn);
	}

	private int _walkingCount(LinkedList<Long> dids, Method limFn, Method cntFn) {
		if (dids == null) {
			dids = mCol.getDecks().active();
		}
		int tot = 0;
		HashMap<Long, Integer> pcounts = new HashMap<Long, Integer>();
		// for each of the active decks
		try {
			for (long did : dids) {
				// get the individual deck's limit
				int lim = 0;
				// if (limFn != null) {
				lim = (Integer) limFn.invoke(Sched.this,
						mCol.getDecks().get(did));
				// }
				if (lim == 0) {
					continue;
				}
				// check the parents
				ArrayList<JSONObject> parents = mCol.getDecks().parents(did);
				for (JSONObject p : parents) {
					// add if missing
					long id = p.getLong("id");
					if (!pcounts.containsKey(id)) {
						pcounts.put(id, (Integer) limFn.invoke(Sched.this, p));
					}
					// take minimum of child and parent
					lim = Math.min(pcounts.get(id), lim);
				}
				// see how many cards we actually have
				int cnt = 0;
				// if (cntFn != null) {
				cnt = (Integer) cntFn.invoke(Sched.this, did, lim);
				// }
				// if non-zero, decrement from parents counts
				for (JSONObject p : parents) {
					long id = p.getLong("id");
					pcounts.put(id, pcounts.get(id) - cnt);
				}
				// we may also be a parent
				pcounts.put(did, lim - cnt);
				// and add to running total
				tot += cnt;
			}
		} catch (JSONException e) {
			throw new RuntimeException(e);
		} catch (InvocationTargetException e) {
			throw new RuntimeException(e);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
		return tot;
	}

	/**
	 * Deck list
	 * ****************************************************************
	 * *******************************
	 */

	/** LIBANKI: not in libanki */
	public Object[] deckCounts() {
    	// check if new day has rolled over and reset counts if yes
//    	if (Utils.now() > mDayCutoff) {
//    		_updateCutoff();
//    	}
       	TreeSet<Object[]> decks = deckDueTree(DECK_INFORMATION_SIMPLE_COUNTS);
       	int[] counts = new int[]{0, 0, 0};
       	for (Object[] deck : decks) {
       		if (((String[])deck[0]).length == 1) {
       			counts[0] += (Integer) deck[2];
       			counts[1] += (Integer) deck[3];
       			counts[2] += (Integer) deck[4];
       		}
       	}
   		return new Object[]{decks, eta(counts), mCol.cardCount()};
	}

	   
    public class DeckDueListComparator implements Comparator<Object[]> {
        public int compare(Object[] o1, Object[] o2) {
            return ((String) o1[0]).compareTo((String) o2[0]);
        }
    }

	/**
	 * Returns [deckname, did, new, lrn, rev]
	 */
	public ArrayList<Object[]> deckDueList(int counts) {
		// DIFFERS FROM LIBANKI: finds all decks, also it swaps the position of new and rev in the results!
		_checkDay();
		if (mClearOverdue) {
			removeFailed(true);
		}
		mCol.getDecks().recoverOrphans();
		ArrayList<Object[]> dids = new ArrayList<Object[]>();
		for (JSONObject g : mCol.getDecks().all()) {
			try {
				long did = g.getLong("id");
				int newCount = -1;
				int lrnCount = -1;
				int revCount = -1;
				float matProgress = -1.0f;
				float allProgress = -1.0f;

				if (counts > DECK_INFORMATION_NAMES) {
					LinkedList<Long> ldid = new LinkedList<Long>();
					ldid.add(did);
					for (Long c : mCol.getDecks().children(did).values()) {
						ldid.add(c);
					}
					String didLimit = Utils.ids2str(ldid);
					newCount = _walkingCount(ldid,
							Sched.class.getDeclaredMethod(
									"_deckNewLimitSingle", JSONObject.class),
							Sched.class.getDeclaredMethod("_cntFnNew",
									long.class, int.class));
					lrnCount = _cntFnLrn(didLimit);
					revCount = _walkingCount(ldid,
							Sched.class.getDeclaredMethod(
									"_deckRevLimitSingle", JSONObject.class),
							Sched.class.getDeclaredMethod("_cntFnRev",
									long.class, int.class));
//					if (counts > DECK_INFORMATION_SIMPLE_COUNTS) {
//						float totalNewCount = newCount(didLimit);
//						float totalCount = cardCount(didLimit);
//						float matureCount = matureCount(didLimit);
//						matProgress = matureCount / totalCount;
//						allProgress = 1 - ((totalNewCount + lrnCount) / totalCount) - matProgress;
//					} else {
//						matProgress = -2.0f;
//						allProgress = -2.0f;
//					}
				}
				dids.add(new Object[] { g.getString("name"), did, newCount,
						lrnCount, revCount, matProgress, allProgress });
			} catch (JSONException e) {
				throw new RuntimeException(e);
			} catch (NoSuchMethodException e) {
				throw new RuntimeException(e);
			}
		}
		Collections.sort(dids, new DeckDueListComparator());
		return dids;
	}

	public TreeSet<Object[]> deckDueTree(int counts) {
		return _groupChildren(deckDueList(counts), counts);
	}

	private TreeSet<Object[]> _groupChildren(ArrayList<Object[]> grps) {
		return _groupChildren(grps, 0);
	}

	private TreeSet<Object[]> _groupChildren(ArrayList<Object[]> grps,
			int counts) {
		TreeSet<Object[]> set = new TreeSet<Object[]>(new DeckNameCompare());
		// first, split the group names into components
		for (Object[] g : grps) {
			set.add(new Object[] { ((String) g[0]).split("::"), g[1], g[2],
					g[3], g[4], g[5], g[6] });
		}
		// if (counts) {
		// // then run main function
		// return _groupChildrenMain(set);
		// } else {
		return set;
		// }
	}

	// private TreeSet<Object[]> _groupChildrenMain(TreeSet<Object[]> grps) {
	// return _groupChildrenMain(grps, 0);
	// }
	// private TreeSet<Object[]> _groupChildrenMain(TreeSet<Object[]> grps, int
	// depth) {
	// TreeSet<Object[]> tree = new TreeSet<Object[]>(new DeckNameCompare());
	// // group and recurse
	// Iterator<Object[]> it = grps.iterator();
	// Object[] tmp = null;
	// while (tmp != null || it.hasNext()) {
	// Object[] head;
	// if (tmp != null) {
	// head = tmp;
	// tmp = null;
	// } else {
	// head = it.next();
	// }
	// String[] title = (String[]) head[0];
	// long did = (Long) head[1];
	// int newCount = (Integer) head[2];
	// int lrnCount = (Integer) head[3];
	// int revCount = (Integer) head[4];
	// TreeSet<Object[]> children = new TreeSet<Object[]>(new
	// DeckNameCompare());
	// while (it.hasNext()) {
	// Object[] o = it.next();
	// if (((String[])o[0])[depth].equals(title[depth])) {
	// // add to children
	// children.add(o);
	// } else {
	// // proceed with this as head
	// tmp = o;
	// break;
	// }
	// }
	// children = _groupChildrenMain(children, depth + 1);
	// // tally up children counts
	// for (Object[] ch : children) {
	// newCount += (Integer)ch[2];
	// lrnCount += (Integer)ch[3];
	// revCount += (Integer)ch[4];
	// }
	// tree.add(new Object[] {title, did, newCount, lrnCount, revCount,
	// children});
	// }
	// TreeSet<Object[]> result = new TreeSet<Object[]>(new DeckNameCompare());
	// for (Object[] t : tree) {
	// result.add(new Object[]{t[0], t[1], t[2], t[3], t[4]});
	// result.addAll((TreeSet<Object[]>) t[5]);
	// }
	// return result;
	// }

	/**
	 * Getting the next card
	 * ****************************************************
	 * *******************************************
	 */

	/**
	 * Return the next due card, or None.
	 */
	private Card _getCard() {
		// learning card due?
		Card c = _getLrnCard();
		if (c != null) {
			return c;
		}
		// new first, or time for one?
		if (_timeForNewCard()) {
			return _getNewCard();
		}
		// Card due for review?
		c = _getRevCard();
		if (c != null) {
			return c;
		}
		// day learning card due?
		c = _getLrnDayCard();
		if (c != null) {
			return c;
		}
		// New cards left?
		c = _getNewCard();
		if (c != null) {
			return c;
		}
		// collapse or finish
		return _getLrnCard(true);
	}

	/** LIBANKI: not in libanki */
	public boolean removeCardFromQueues(Card card) {
		long id = card.getId();
		Iterator<long[]> i = mNewQueue.iterator();
		while (i.hasNext()) {
			long cid = i.next()[0];
			if (cid == id) {
				i.remove();
				mNewCount -= 1;
				return true;
			}
		}
		i = mLrnQueue.iterator();
		while (i.hasNext()) {
			long cid = i.next()[1];
			if (cid == id) {
				i.remove();
				mLrnCount -= card.getLeft();
				return true;
			}
		}
		i = mRevQueue.iterator();
		while (i.hasNext()) {
			long cid = i.next()[0];
			if (cid == id) {
				i.remove();
				mRevCount -= 1;
				return true;
			}
		}
		return false;
	}

	/**
	 * New cards
	 * ****************************************************************
	 * *******************************
	 */

	private void _resetNewCount() {
		try {
			mNewCount = _walkingCount(Sched.class.getDeclaredMethod(
					"_deckNewLimitSingle", JSONObject.class),
					Sched.class.getDeclaredMethod("_cntFnNew", long.class,
							int.class));
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}

	private int _cntFnNew(long did, int lim) {
		return mCol.getDb().queryScalar(
				"SELECT count() FROM (SELECT 1 FROM cards WHERE did = " + did
						+ " AND queue = 0 LIMIT " + lim + ")");
	}

	private void _resetNew() {
		_resetNewCount();
		mNewDids = new LinkedList<Long>(mCol.getDecks().active());
		mNewQueue.clear();
		_updateNewCardRatio();
	}

	private boolean _fillNew() {
		if (mNewQueue.size() > 0) {
			return true;
		}
		if (mNewCount == 0) {
			return false;
		}
		while (!mNewDids.isEmpty()) {
			long did = mNewDids.getFirst();
			int lim = Math.min(mQueueLimit, _deckNewLimit(did));
			mNewQueue.clear();
			Cursor cur = null;
			if (lim != 0) {
				try {
					cur = mCol
							.getDb()
							.getDatabase()
							.rawQuery(
									"SELECT id, due FROM cards WHERE did = "
											+ did + " AND queue = 0 LIMIT "
											+ lim, null);
					while (cur.moveToNext()) {
						mNewQueue.add(new long[] { cur.getLong(0),
								cur.getLong(1) });
					}
				} finally {
					if (cur != null && !cur.isClosed()) {
						cur.close();
					}
				}
				if (!mNewQueue.isEmpty()) {
					return true;
				}
			}
			// nothing left in the deck; move to next
			mNewDids.remove();
		}
		return false;
	}

	private Card _getNewCard() {
		if (!_fillNew()) {
			return null;
		}
		long[] item = mNewQueue.remove();
		// move any siblings to the end?
		try {
			JSONObject conf = mCol.getDecks().confForDid(mNewDids.getFirst());
			if (conf.getInt("dyn") != 0 || conf.getJSONObject("new").getBoolean("separate")) {
				int n = mNewQueue.size();
				while (!mNewQueue.isEmpty()
						&& mNewQueue.getFirst()[1] == item[1]) {
					mNewQueue.add(mNewQueue.remove());
					n -= 1;
					if (n == 0) {
						// we only have one fact in the queue; stop rotating
						break;
					}
				}
			}
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
		mNewCount -= 1;
		return mCol.getCard(item[0]);
	}

	private void _updateNewCardRatio() {
		try {
			if (mCol.getConf().getInt("newSpread") == NEW_CARDS_DISTRIBUTE) {
				if (mNewCount != 0) {
					mNewCardModulus = (mNewCount + mRevCount) / mNewCount;
					// if there are cards to review, ensure modulo >= 2
					if (mRevCount != 0) {
						mNewCardModulus = Math.max(2, mNewCardModulus);
					}
					return;
				}
			}
			mNewCardModulus = 0;
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * @return True if it's time to display a new card when distributing.
	 */
	private boolean _timeForNewCard() {
		if (mNewCount == 0) {
			return false;
		}
		int spread;
		try {
			spread = mCol.getConf().getInt("newSpread");
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
		if (spread == NEW_CARDS_LAST) {
			return false;
		} else if (spread == NEW_CARDS_FIRST) {
			return true;
		} else if (mNewCardModulus != 0) {
			return (mReps != 0 && (mReps % mNewCardModulus == 0));
		} else {
			return false;
		}
	}

	private int _deckNewLimit(long did) {
		return _deckNewLimit(did, null);
	}

	private int _deckNewLimit(long did, Method fn) {
		try {
			if (fn == null) {
				fn = Sched.class.getDeclaredMethod("_deckNewLimitSingle",
						JSONObject.class);
			}
			ArrayList<JSONObject> decks = mCol.getDecks().parents(did);
			decks.add(mCol.getDecks().get(did));
			int lim = -1;
			// for the deck and each of its parents
			int rem = 0;
			for (JSONObject g : decks) {
				rem = (Integer) fn.invoke(Sched.this, g);
				if (lim == -1) {
					lim = rem;
				} else {
					lim = Math.min(rem, lim);
				}
			}
			return lim;
		} catch (IllegalArgumentException e) {
			throw new RuntimeException(e);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		} catch (InvocationTargetException e) {
			throw new RuntimeException(e);
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}

	/* Limit for deck without parent limits. */
	public int _deckNewLimitSingle(JSONObject g) {
		try {
			if (g.getInt("dyn") != 0) {
				return mReportLimit;
			}
			JSONObject c = mCol.getDecks().confForDid(g.getLong("id"));
			return Math.max(0, c.getJSONObject("new").getInt("perDay")
					- g.getJSONArray("newToday").getInt(1));
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Learning queues
	 * ***********************************************************
	 * ************************************
	 */

	private void _resetLrnCount() {
		mLrnCount = _cntFnLrn(_deckLimit());
		// day
		mLrnCount += (int) mCol.getDb().queryScalar(
				"SELECT count() FROM cards WHERE did IN " + _deckLimit() + " AND queue = 3 AND due <= " + mToday + " LIMIT " + mReportLimit, false);
	}

	private int _cntFnLrn(String dids) {
		return (int) mCol.getDb().queryScalar(
				"SELECT sum(left / 1000) FROM (SELECT left FROM cards WHERE did IN "
						+ dids + " AND queue = 1 AND due < " + mDayCutoff
						+ " LIMIT " + mReportLimit + ")", false);
	}

	private void _resetLrn() {
		_resetLrnCount();
		mLrnQueue.clear();
		mLrnDayQueue.clear();
		mLrnDids = mCol.getDecks().active();
	}

	// sub-day learning
	private boolean _fillLrn() {
		if (mLrnCount == 0) {
			return false;
		}
		if (!mLrnQueue.isEmpty()) {
			return true;
		}
		Cursor cur = null;
		mLrnQueue.clear();
		try {
			cur = mCol
					.getDb()
					.getDatabase()
					.rawQuery(
							"SELECT due, id FROM cards WHERE did IN "
									+ _deckLimit()
									+ " AND queue = 1 AND due < " + mDayCutoff
									+ " LIMIT " + mReportLimit,
							null);
			while (cur.moveToNext()) {
				mLrnQueue.add(new long[] { cur.getLong(0), cur.getLong(1) });
			}
			// as it arrives sorted by did first, we need to sort it
			Collections.sort(mLrnQueue, new DueComparator());
			return !mLrnQueue.isEmpty();
		} finally {
			if (cur != null && !cur.isClosed()) {
				cur.close();
			}
		}
	}

	private Card _getLrnCard() {
		return _getLrnCard(false);
	}

	private Card _getLrnCard(boolean collapse) {
		if (_fillLrn()) {
			double cutoff = Utils.now();
			if (collapse) {
				try {
					cutoff += mCol.getConf().getInt("collapseTime");
				} catch (JSONException e) {
					throw new RuntimeException(e);
				}
			}
			if (mLrnQueue.getFirst()[0] < cutoff) {
				long id = mLrnQueue.remove()[1];
				Card card = mCol.getCard(id);
				mLrnCount -= card.getLeft() / 1000;
				return card;
			}
		}
		return null;
	}

	// daily learning
	private boolean _fillLrnDay() {
		if (mLrnCount == 0) {
			return false;
		}
		if (!mLrnDayQueue.isEmpty()) {
			return true;
		}
		while (mLrnDids.size() > 0) {
			long did = mLrnDids.getFirst();
			// fill the queue with the current did
			mLrnDayQueue.clear();
			Cursor cur = null;
			try {
				cur = mCol
						.getDb()
						.getDatabase()
						.rawQuery(
								"SELECT id FROM cards WHERE did = " + did
										+ " AND queue = 3 AND due <= "
										+ mToday + " LIMIT " + mQueueLimit, null);
				while (cur.moveToNext()) {
					mLrnDayQueue.add(new long[] { cur.getLong(0) });
				}
			} finally {
				if (cur != null && !cur.isClosed()) {
					cur.close();
				}
			}
			if (mLrnDayQueue.size() > 0) {
				// order
				Random r = new Random();
				r.setSeed(mToday);
				Collections.shuffle(mLrnDayQueue, r);
				// is the current did empty?
				if (mLrnDayQueue.size() < mQueueLimit) {
					mLrnDids.remove();
				}
				return true;
			}
			// nothing left in the deck; move to next
			mLrnDids.remove();
		}
		return false;
	}

	private Card _getLrnDayCard() {
		if (_fillLrnDay()) {
			mLrnCount -= 1;
			return mCol.getCard(mLrnDayQueue.remove()[0]);
		}
		return null;
	}
	/**
	 * @param ease
	 *            1=no, 2=yes, 3=remove
	 */
	private void _answerLrnCard(Card card, int ease) {
		JSONObject conf = _lrnConf(card);
		int type;
		if (card.getODid() != 0) {
			type = 3;
		} else if (card.getType() == 2) {
			type = 2;
		} else {
			type = 0;
		}
		boolean leaving = false;
		// lrnCount was decremented once when card was fetched
		int lastLeft = card.getLeft();
		// immediate graduate?
		if (ease == 3) {
			_rescheduleAsRev(card, conf, true);
			leaving = true;
			// graduation time?
		} else if (ease == 2 && (card.getLeft() % 1000) - 1 <= 0) {
			_rescheduleAsRev(card, conf, false);
			leaving = true;
		} else {
			// one step towards graduation
			if (ease == 2) {
				// decrement real left count and recalculate left today
				int left = (card.getLeft() % 1000) - 1;
				try {
					card.setLeft(_leftToday(conf.getJSONArray("delays"), left) * 1000 + left);
				} catch (JSONException e) {
					throw new RuntimeException(e);
				}
				// failed
			} else {
				card.setLeft(_startingLeft(card));
				if (card.getODid() != 0) {
					if (conf.has("mult")) {
						// review that's lapsed
						try {
							card.setIvl(Math.max(1, card.getIvl() * conf.getInt("mult")));
						} catch (JSONException e) {
							throw new RuntimeException(e);
						}
					} else {
						// new card; no ivl adjustment
						// pass
					}
					card.setODue(mToday + 1);
				}
			}
			int delay = _delayForGrade(conf, card.getLeft());
			if (card.getDue() < Utils.now()) {
				// not collapsed; add some randomness
				delay *= (1 + (new Random().nextInt(25) / 100));
			}
			// TODO: check, if type for second due is correct
			card.setDue((int) (Utils.now() + delay));
			if (card.getDue() < mDayCutoff) {
				mLrnCount += card.getLeft() / 1000;
				// if the queue is not empty and there's nothing else to do, make
				// sure we don't put it at the head of the queue and end up showing
				// it twice in a row
				card.setQueue(1);
				if (!mLrnQueue.isEmpty() && mRevCount == 0 && mNewCount == 0) {
					long smallestDue = mLrnQueue.getFirst()[0];
					card.setDue(Math.max(card.getDue(), smallestDue + 1));
				}
				_sortIntoLrn(card.getDue(), card.getId());
			} else {
				// the card is due in one or more days, so we need to use the day learn queue
				long ahead = ((card.getDue() - mDayCutoff) / 86400) + 1;
				card.setDue(mToday + ahead);
				card.setQueue(3);
			}
		}
		_logLrn(card, ease, conf, leaving, type, lastLeft);
	}

	/**
	 * Sorts a card into the lrn queue LIBANKI: not in libanki
	 */
	private void _sortIntoLrn(long due, long id) {
		Iterator i = mLrnQueue.listIterator();
		int idx = 0;
		while (i.hasNext()) {
			if (((long[]) i.next())[0] > due) {
				break;
			} else {
				idx++;
			}
		}
		mLrnQueue.add(idx, new long[] { due, id });
	}

	private int _delayForGrade(JSONObject conf, int left) {
		left = left % 1000;
		try {
			double delay;
			JSONArray ja = conf.getJSONArray("delays");
			int len = ja.length();
			try {
				delay = ja.getDouble(len - left);
			} catch (JSONException e) {
				delay = ja.getDouble(0);
			}
			return (int) (delay * 60.0);
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	private JSONObject _lrnConf(Card card) {
		if (card.getType() == 2) {
			return _lapseConf(card);
		} else {
			return _newConf(card);
		}
	}

	private void _rescheduleAsRev(Card card, JSONObject conf, boolean early) {
		if (card.getType() == 2) {
			card.setDue(Math.max(mToday + 1, card.getODue()));
			card.setODue(0);
		} else {
			_rescheduleNew(card, conf, early);
		}
		card.setQueue(2);
		card.setType(2);
		// if we were dynamic, graduating means moving back to the old deck
		if (card.getODid() != 0) {
			card.setDid(card.getODid());
			card.setODue(0);
			card.setODid(0);
		}
	}

	private int _startingLeft(Card card) {
		try {
			JSONObject conf = _lrnConf(card);
			int tot = conf.getJSONArray("delays").length();
			int tod = _leftToday(conf.getJSONArray("delays"), tot);
			return tot + tod * 1000;
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	/* the number of steps that can be completed by the day cutoff */
	private int _leftToday(JSONArray delays, int left) {
		return _leftToday(delays, left, 0);
	}
	private int _leftToday(JSONArray delays, int left, long now) {
		if (now == 0) {
			now = Utils.intNow();
		}
		int ok = 0;
		int offset = Math.min(left, delays.length());
		for (int i = 0; i < offset; i++) {
			try {
				now += (int)(delays.getDouble(delays.length() - offset + i) * 60.0);
			} catch (JSONException e) {
				throw new RuntimeException(e);
			}
			if (now > mDayCutoff) {
				break;
			}
			ok = i;
		}
		return ok + 1;
	}

	private int _graduatingIvl(Card card, JSONObject conf, boolean early) {
		return _graduatingIvl(card, conf, early, true);
	}

	private int _graduatingIvl(Card card, JSONObject conf, boolean early,
			boolean adj) {
		if (card.getType() == 2) {
			// lapsed card being relearnt
			if (card.getODid() != 0) {
				return _dynIvlBoost(card);
			}
			return card.getIvl();
		}
		int ideal;
		JSONArray ja;
		try {
			ja = conf.getJSONArray("ints");
			if (!early) {
				// graduate
				ideal = ja.getInt(0);
			} else {
				ideal = ja.getInt(1);
			}
			if (adj) {
				return _adjRevIvl(card, ideal);
			} else {
				return ideal;
			}
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	/* Reschedule a new card that's graduated for the first time. */
	private void _rescheduleNew(Card card, JSONObject conf, boolean early) {
		card.setIvl(_graduatingIvl(card, conf, early));
		card.setDue(mToday + card.getIvl());
		try {
			card.setFactor(conf.getInt("initialFactor"));
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	private void _logLrn(Card card, int ease, JSONObject conf, boolean leaving,
			int type, int lastLeft) {
		int lastIvl = -(_delayForGrade(conf, lastLeft));
		int ivl = leaving ? card.getIvl() : -(_delayForGrade(conf,
				card.getLeft()));
		log(card.getId(), mCol.usn(), ease, ivl, lastIvl, card.getFactor(),
				card.timeTaken(), type);
	}

	private void log(long id, int usn, int ease, int ivl, int lastIvl,
			int factor, int timeTaken, int type) {
		try {
			mCol.getDb()
					.getDatabase()
					.execSQL(
							"INSERT INTO revlog VALUES (?,?,?,?,?,?,?,?,?)",
							new Object[] { Utils.now() * 1000, id, usn, ease,
									ivl, lastIvl, factor, timeTaken, type });
		} catch (SQLiteConstraintException e) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException e1) {
				throw new RuntimeException(e1);
			}
			log(id, usn, ease, ivl, lastIvl, factor, timeTaken, type);
		}
	}

	public void removeFailed() {
		removeFailed(null);
	}

	private void removeFailed(long[] ids) {
		removeFailed(ids, false);
	}
	private void removeFailed(boolean expiredOnly) {
		removeFailed(null, expiredOnly);
	}
	/**
	 * Remove failed cards from the learning queue.
	 */
	private void removeFailed(long[] ids, boolean expiredOnly) {
		String extra;
		if (ids != null && ids.length > 0) {
			extra = " AND id IN " + Utils.ids2str(ids);
		} else {
			// benchmarks indicate it's about 10x faster to search all decks with the index than scan the table
			extra = " AND did IN " + Utils.ids2str(mCol.getDecks().allIds());
		}
		if (expiredOnly) {
			extra += " AND odue <= " + mToday;
		}
		boolean mod = mCol.getDb().getMod();
		mCol.getDb().execute(String.format(Locale.US, "update cards set " +
				"due = odue, queue = 2, mod = %d, usn = %d, odue = 0 " +
				"where queue = 1 and type = 2 %s", Utils.intNow(), mCol.usn(), extra));
		if (expiredOnly) {
			// we don't want to bump the mod time when removing expired
			mCol.getDb().setMod(mod);
		}
	}

	private int _lrnForDeck(long did) {
		try {
			return mCol.getDb().queryScalar("SELECT sum(left / 1000) FROM (SELECT left FROM cards WHERE did = " + did + " AND queue = 1 AND due < " + (Utils.intNow() + mCol.getConf().getInt("collapseTime")) + " LIMIT " + mReportLimit, false);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}
	/**
	 * Reviews
	 * ******************************************************************
	 * *****************************
	 */

	private int _deckRevLimit(long did) {
		try {
			return _deckNewLimit(did, Sched.class.getDeclaredMethod(
					"_deckRevLimitSingle", JSONObject.class));
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}

	private int _deckRevLimitSingle(JSONObject d) {
		try {
			if (d.getInt("dyn") != 0) {
				return mReportLimit;
			}
			JSONObject c = mCol.getDecks().confForDid(d.getLong("id"));
			return Math.max(0, c.getJSONObject("rev").getInt("perDay")
					- d.getJSONArray("revToday").getInt(1));
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	private void _resetRevCount() {
		try {
			mRevCount = _walkingCount(Sched.class.getDeclaredMethod(
					"_deckRevLimitSingle", JSONObject.class),
					Sched.class.getDeclaredMethod("_cntFnRev", long.class,
							int.class));
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}

	private int _cntFnRev(long did, int lim) {
		return mCol.getDb().queryScalar(
				"SELECT count() FROM (SELECT id FROM cards WHERE did = " + did
						+ " AND queue = 2 and due <= " + mToday + " LIMIT "
						+ lim + ")");
	}

	private void _resetRev() {
		_resetRevCount();
		mRevQueue.clear();
		mRevDids = mCol.getDecks().active();
	}

	private boolean _fillRev() {
		if (!mRevQueue.isEmpty()) {
			return true;
		}
		if (mRevCount == 0) {
			return false;
		}
		while (mRevDids.size() > 0) {
			long did = mRevDids.getFirst();
			int lim = Math.min(mQueueLimit, _deckRevLimit(did));
			mRevQueue.clear();
			Cursor cur = null;
			if (lim != 0) {
				// fill the queue with the current did
				try {
					cur = mCol
							.getDb()
							.getDatabase()
							.rawQuery(
									"SELECT id FROM cards WHERE did = " + did
											+ " AND queue = 2 AND due <= "
											+ mToday + " LIMIT "
											+ lim, null);
					while (cur.moveToNext()) {
						mRevQueue.add(new long[] { cur.getLong(0) });
					}
				} finally {
					if (cur != null && !cur.isClosed()) {
						cur.close();
					}
				}
				if (!mRevQueue.isEmpty()) {
					// ordering
					try {
						if (mCol.getDecks().get(did).getInt("dyn") != 0) {
							// dynamic decks need due order preserved
						} else {
							Random r = new Random();
							r.setSeed(mToday);
							Collections.shuffle(mRevQueue, r);
						}
					} catch (JSONException e) {
						throw new RuntimeException(e);
					}
					// is the current did empty?
					if (mRevQueue.size() < lim) {
						mRevDids.remove();
					}
					return true;
				}
			}
			// nothing left in the deck; move to next
			mRevDids.remove();
		}
		return false;
	}

	private Card _getRevCard() {
		if (_fillRev()) {
			mRevCount -= 1;
			return mCol.getCard(mRevQueue.remove()[0]);
		} else {
			return null;
		}
	}

	/**
	 * Answering a review card
	 * **************************************************
	 * *********************************************
	 */

	private boolean _answerRevCard(Card card, int ease) {
	    int delay = 0;
		boolean leech = false;
		if (ease == 1) {
		    Pair<Integer, Boolean> res = _rescheduleLapse(card);
		    delay = res.first;
		    leech = res.second;
		} else {
			_rescheduleRev(card, ease);
		}
		_logRev(card, ease, delay);
		return leech;
	}

	private Pair<Integer, Boolean> _rescheduleLapse(Card card) {
		JSONObject conf;
		try {
			conf = _lapseConf(card);
			card.setLapses(card.getLapses() + 1);
			card.setLastIvl(card.getIvl());
			card.setIvl(_nextLapseIvl(card, conf));
			card.setFactor(Math.max(1300, card.getFactor() - 200));
			card.setDue(mToday + card.getIvl());
			// put back in learn queue?
			int delay = 0;
			if (conf.getJSONArray("delays").length() > 0) {
				card.setODue(card.getDue());
				delay = _delayForGrade(conf, 0);
				card.setDue((long) (delay + Utils.now()));
				int left = conf.getJSONArray("delays").length();
				card.setLeft(left + _leftToday(conf.getJSONArray("delays"), left) * 1000);
				card.setQueue(1);
				mLrnCount += card.getLeft() / 1000;
			}
			// leech?
			if (!_checkLeech(card, conf)
					&& conf.getJSONArray("delays").length() > 0) {
				_sortIntoLrn(card.getDue(), card.getId());
				return new Pair<Integer, Boolean>(delay, false);
			} else {
                return new Pair<Integer, Boolean>(delay, true);
			}
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	private int _nextLapseIvl(Card card, JSONObject conf) {
		try {
			return (int) (card.getIvl() * conf.getInt("mult")) + 1;
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	private void _rescheduleRev(Card card, int ease) {
		// update interval
		card.setLastIvl(card.getIvl());
		_updateRevIvl(card, ease);
		// then the rest
		card.setFactor(Math.max(1300, card.getFactor()
				+ FACTOR_ADDITION_VALUES[ease - 2]));
		card.setDue(mToday + card.getIvl());
		if (card.getODid() != 0) {
			card.setDid(card.getODid());
			card.setODid(0);
			card.setODue(0);
		}
	}

	private void _logRev(Card card, int ease, int delay) {
		log(card.getId(), mCol.usn(), ease, ((delay != 0) ? (-delay) : card.getIvl()),
				card.getLastIvl(), card.getFactor(), card.timeTaken(), 1);
	}

	/**
	 * Interval management
	 * ******************************************************
	 * *****************************************
	 */

	/**
	 * Ideal next interval for CARD, given EASE.
	 */
	private int _nextRevIvl(Card card, int ease) {
		long delay = _daysLate(card);
		double interval = 0;
		JSONObject conf = _revConf(card);
		double fct = card.getFactor() / 1000.0;
		if (ease == 2) {
			interval = (card.getIvl() + delay / 4) * 1.2;
		} else if (ease == 3) {
			interval = (card.getIvl() + delay / 2) * fct;
		} else if (ease == 4) {
			try {
				interval = (card.getIvl() + delay) * fct
						* conf.getDouble("ease4");
			} catch (JSONException e) {
				throw new RuntimeException(e);
			}
		}
		// apply interval factor adjustment
		interval = _ivlWithFactor(conf, interval);
		// must be at least one day greater than previous interval; two if easy
		return Math.max(card.getIvl() + (ease == 4 ? 2 : 1), (int) interval);
	}

	private double _ivlWithFactor(JSONObject conf, double ivl) {
		try {
			return ivl * conf.getDouble("ivlfct");
		} catch (JSONException e) {
			return 1;
		}
	}

	/**
	 * Number of days later than scheduled.
	 */
	private long _daysLate(Card card) {
		long due = card.getODid() != 0 ? card.getODue() : card.getDue();
		return Math.max(0, mToday - due);
	}

	/**
	 * Update CARD's interval, trying to avoid siblings.
	 */
	private void _updateRevIvl(Card card, int ease) {
		int idealIvl = _nextRevIvl(card, ease);
		card.setIvl(_adjRevIvl(card, idealIvl));
	}

	/**
	 * Given IDEALIVL, return an IVL away from siblings.
	 */
	private int _adjRevIvl(Card card, int idealIvl) {
		int idealDue = mToday + idealIvl;
		JSONObject conf;
		try {
			conf = _revConf(card);
			// find sibling positions
			ArrayList<Integer> dues = mCol.getDb().queryColumn(
					Integer.class,
					"SELECT due FROM cards WHERE nid = " + card.getNid()
							+ " AND type = 2 AND id != " + card.getId(), 0);
			if (dues.size() == 0 || !dues.contains(idealDue)) {
				return idealIvl;
			} else {
				int leeway = Math.max(conf.getInt("minSpace"),
						(int) (idealIvl * conf.getDouble("fuzz")));
				int fudge = 0;
				// do we have any room to adjust the interval?
				if (leeway != 0) {
					// loop through possible due dates for an empty one
					for (int diff = 1; diff < leeway + 1; diff++) {
						// ensure we're due at least tomorrow
						if ((idealIvl - diff >= 1)
								&& !dues.contains(idealDue - diff)) {
							fudge = -diff;
							break;
						} else if (!dues.contains(idealDue + diff)) {
							fudge = diff;
							break;
						}
					}
				}
				return idealIvl + fudge;
			}
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Dynamic deck handling
	 * ******************************************************************
	 * *****************************
	 */

	/* Rebuild a dynamic deck. */
	public void rebuildDyn() {
		rebuildDyn(0);
	}
	public void rebuildDyn(long did) {
		if (did == 0) {
			did = mCol.getDecks().selected();
		}
		JSONObject deck = mCol.getDecks().get(did);
		try {
			if (deck.getInt("dyn") == 0) {
				Log.e(AnkiDroidApp.TAG, "error: deck is not a dynamic deck");
				return;
			}
		} catch (JSONException e1) {
			throw new RuntimeException(e1);
		}
		// move any existing cards back first
		remDyn(did);
		// gather card ids and sort
		String order = _dynOrder(deck);
		String limit;
		ArrayList<Long> ids;
		String search;
		try {
			limit = " LIMIT " + deck.getInt("limit");
			search = deck.getString("search") + " -is:suspended";
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
		try {
			ids = mCol.findCards(search, order + limit);			
		} catch (RuntimeException e) {
			ids = new ArrayList<Long>();
		}
		// move the cards over
		_moveToDyn(did, ids);
		// and change to our new deck
		mCol.getDecks().select(did);
	}

	public void remDyn(long did) {
		remDyn(did, null);
	}
	public void remDyn(long did, String lim) {
		if (lim == null) {
			lim = "did = " + did;
		}
		// move out of cram queue
		mCol.getDb().execute("UPDATE cards SET did = odid, queue = (CASE WHEN type = 1 THEN 0 ELSE type END), type = (CASE WHEN type = 1 THEN 0 ELSE type END), due = odue, odue = 0, odid = 0, usn = " + mCol.usn() + ", mod = " + Utils.intNow() + " WHERE " + lim);
	}

	public void remFromDyn(long[] cids) {
		remDyn(0, "id IN " + Utils.ids2str(cids) + " AND odid");
	}

	private String _dynOrder(JSONObject deck) {
		int o;
		try {
			o = deck.getInt("order");
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
		switch (o) {
		case DYN_OLDEST: 
			return "order by c.mod";
		case DYN_RANDOM: 
			return "order by random()";
		case DYN_SMALLINT: 
			return "order by ivl";
		case DYN_BIGINT: 
			return "order by ivl desc";
		case DYN_LAPSES:
			return "order by lapses desc";
		case DYN_FAILED: 
			return "AND c.id IN (SELECT cid FROM revlog WHERE ease = 1 AND time > " + ((mDayCutoff - 86400) * 1000) + ") order by c.mod";
		case DYN_ADDED: 
			return "order by n.id";
		case DYN_DUE:
			return "order by c.due";
		default:
			return "";
		}
	}

	private void _moveToDyn(long did, ArrayList<Long> ids) {
		JSONObject deck = mCol.getDecks().get(did);
		ArrayList<Object[]> data = new ArrayList<Object[]>();
		long t = Utils.intNow();
		int u = mCol.usn();
		for (long c = 0; c < ids.size(); c++) {
			// start at -100000 so that reviews are all due
			data.add(new Object[]{did, -100000 + c, t, u, ids.get((int)c)});
		}
		String queue;
		try {
			if (deck.getBoolean("cramRev")) {
				// everything in the new queue
				queue = "0";
			} else {
				// due reviews stay in the review queue. careful: can't use "odid or did", as sqlite converts to boolean
				queue = "(CASE WHEN type = 2 AND (CASE WHEN odue THEN odue <= " + mToday + " ELSE due <= " + mToday + " END) THEN 2 ELSE 0 END)";
			}
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
		mCol.getDb().executeMany("UPDATE cards SET odid = (CASE WHEN odid THEN odid ELSE did END), odue = (CASE WHEN odue THEN odue ELSE due END), did = ?, queue = " + queue + ", due = ?, mod = ?, usn = ? WHERE id = ?", data);
	}

	private int _dynIvlBoost(Card card) {
		if (card.getODid() == 0 || card.getType() != 2 || card.getFactor() == 0) {
			Log.e(AnkiDroidApp.TAG, "error: deck is not a dynamic deck");
			return 0;
		}
		long elapsed = card.getIvl() - (card.getODue() - mToday);
		double factor = ((card.getFactor() / 1000.0) + 1.2) / 2.0;
		return Math.max(1, Math.max(card.getIvl(), (int)(elapsed * factor)));
	}
	
	/**
	 * Leeches
	 * ******************************************************************
	 * *****************************
	 */

	/** Leech handler. True if card was a leech. */
	private boolean _checkLeech(Card card, JSONObject conf) {
		int lf;
		try {
			lf = conf.getInt("leechFails");
			if (lf == 0) {
				return false;
			}
			// if over threshold or every half threshold reps after that
			if (card.getLapses() >= lf
					&& (card.getLapses() - lf) % Math.max(lf / 2, 1) == 0) {
				// add a leech tag
				Note n = card.note();
				n.addTag("leech");
				n.flush();
				// handle
				if (conf.getInt("leechAction") == 0) {
					// if it has an old due, remove it from cram/relearning
					if (card.getODue() != 0) {
						card.setDue(card.getODue());
					}
					if (card.getODid() != 0) {
						card.setDid(card.getODid());
					}
					card.setODue(0);
					card.setODid(0);
					card.setQueue(-1);
				}
				return true;
			}
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
		return false;
	}

	/** LIBANKI: not in libanki */
	public boolean leechActionSuspend(Card card) {
		JSONObject conf;
		try {
			conf = _cardConf(card).getJSONObject("lapse");
			if (conf.getInt("leechAction") == 0) {
				return true;
			} else {
				return false;
			}
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Tools
	 * ********************************************************************
	 * ***************************
	 */

	public JSONObject _cardConf(Card card) {
		return mCol.getDecks().confForDid(card.getDid());
	}

	private JSONObject _newConf(Card card) {
		try {
			JSONObject conf = _cardConf(card);
			if (card.getODid() == 0) {
				return conf.getJSONObject("new");
			} 
			// dynamic deck; override some attributes, use original deck for others
			JSONObject oconf = mCol.getDecks().confForDid(card.getODid());
			JSONObject dict = new JSONObject();
			// original deck
			dict.put("ints", oconf.getJSONObject("new").getJSONArray("ints"));
			dict.put("initialFactor", oconf.getJSONObject("new").getInt("initialFactor"));
			// overrides
			dict.put("delays", conf.getJSONArray("delays"));
			dict.put("separate", conf.getBoolean("separate"));
			dict.put("order", NEW_CARDS_DUE);
			dict.put("perDay", mReportLimit);
			return dict;
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	private JSONObject _lapseConf(Card card) {
		try {
			JSONObject conf = _cardConf(card);
			// normal deck
			if (card.getODid() == 0) {
				return conf.getJSONObject("lapse");
			} 
			// dynamic deck; override some attributes, use original deck for others
			JSONObject oconf = mCol.getDecks().confForDid(card.getODid());
			JSONObject dict = new JSONObject();
			// original deck
			dict.put("minInt", oconf.getJSONObject("lapse").getInt("minInt"));
			dict.put("leechFails", oconf.getJSONObject("lapse").getInt("leechFails"));
			dict.put("leechAction", oconf.getJSONObject("lapse").getInt("leechAction"));
			// overrides
			dict.put("delays", conf.getJSONArray("delays"));
			dict.put("mult", conf.getInt("fmult"));
			return dict;
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	private JSONObject _revConf(Card card) {
		try {
			JSONObject conf = _cardConf(card);
			if (card.getODid() == 0) {
				return conf.getJSONObject("rev");
			} 
			// dynamic deck; override some attributes, use original deck for others
			JSONObject oconf = mCol.getDecks().confForDid(card.getODid());
			JSONObject dict = new JSONObject();
			// original deck
			dict.put("ease4", oconf.getJSONObject("rev").getDouble("ease4"));
			dict.put("ivlfct", oconf.getJSONObject("rev").optDouble("ivlfct", 1.0));
			dict.put("minSpace", oconf.getJSONObject("rev").getInt("minSpace"));
			dict.put("fuzz", oconf.getJSONObject("rev").getDouble("fuzz"));
			return dict;
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	public String _deckLimit() {
		return Utils.ids2str(mCol.getDecks().active());
	}

	/**
	 * Daily cutoff
	 * *************************************************************
	 * **********************************
	 */

	public void _updateCutoff() {
		// days since col created
		mToday = (int) ((Utils.now() - mCol.getCrt()) / 86400);
		// end of day cutoff
		mDayCutoff = mCol.getCrt() + ((mToday + 1) * 86400);

		// this differs from libanki: updates all decks
		for (JSONObject d : mCol.getDecks().all()) {
			update(d);
		}
		// update all daily counts, but don't save decks to prevent needless conflicts. we'll save on card answer instead
		for (JSONObject deck : mCol.getDecks().all()) {
			update(deck);
		}
	}

	private void update(JSONObject g) {
		for (String t : new String[] { "new", "rev", "lrn", "time" }) {
			String k = t + "Today";
			try {
				if (g.getJSONArray(k).getInt(0) != mToday) {
					JSONArray ja = new JSONArray();
					ja.put(mToday);
					ja.put(0);
					g.put(k, ja);
				}
			} catch (JSONException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public boolean _checkDay() {
		// check if the day has rolled over
		if (Utils.now() > mDayCutoff) {
			reset();
			return true;
		}
		return false;
	}

	/**
	 * Deck finished state
	 * ******************************************************
	 * *****************************************
	 */

	public CharSequence finishedMsg(Context context) {
		SpannableStringBuilder sb = new SpannableStringBuilder();
		sb.append(context.getString(R.string.studyoptions_congrats_finished));
		StyleSpan boldSpan = new StyleSpan(Typeface.BOLD);
		sb.setSpan(boldSpan, 0, sb.length(), 0);
		sb.append(_nextDueMsg(context));
//		sb.append("\n\n");
//		sb.append(_tomorrowDueMsg(context));
		return sb;
	}

//	public String _tomorrowDueMsg(Context context) {
//		int newCards = 12;// deck.getSched().newTomorrow();
//		int revCards = 1;// deck.getSched().revTomorrow() +
//		int eta = 0; // TODO
//		Resources res = context.getResources();
//		String newCardsText = res.getQuantityString(
//				R.plurals.studyoptions_congrats_new_cards, newCards, newCards);
//		String etaText = res.getQuantityString(
//				R.plurals.studyoptions_congrats_eta, eta, eta);
//		return res.getQuantityString(R.plurals.studyoptions_congrats_message,
//				revCards, revCards, newCardsText, etaText);
//	}

	public String _nextDueMsg(Context context) {
		StringBuilder sb = new StringBuilder();
		if (revDue()) {
			sb.append("\n\n");
			sb.append(context
					.getString(R.string.studyoptions_congrats_more_rev));
		}
		if (newDue()) {
			sb.append("\n\n");
			sb.append(context
					.getString(R.string.studyoptions_congrats_more_new));
		}
		return sb.toString();
	}

	// /**
	// * Number of rev/lrn cards due tomorrow.
	// */
	// public int revTomorrow() {
	// TODO: _walkingCount...
	// return mCol.getDb().queryScalar(
	// "SELECT count() FROM cards WHERE type > 0 AND queue != -1 AND due = "
	// + (mDayCutoff + 86400) + " AND did IN " + _deckLimit());
	// }

	/** true if there are any rev cards due. */
	public boolean revDue() {
		return mCol.getDb().queryScalar(
				"SELECT 1 FROM cards WHERE did IN " + _deckLimit()
						+ " AND queue = 2 AND due <= " + mToday + " LIMIT 1",
				false) != 0;
	}

	/** true if there are any new cards due. */
	public boolean newDue() {
		return mCol.getDb().queryScalar(
				"SELECT 1 FROM cards WHERE did IN " + _deckLimit()
						+ " AND queue = 0 LIMIT 1", false) != 0;
	}

	/**
	 * Next time reports
	 * ********************************************************
	 * ***************************************
	 */

	/**
	 * Return the next interval for CARD as a string.
	 */
	public String nextIvlStr(Card card, int ease) {
		return Utils.fmtTimeSpan(nextIvl(card, ease));
	}

	/**
	 * Return the next interval for CARD, in seconds.
	 */
	public int nextIvl(Card card, int ease) {
		try {
			if (card.getQueue() == 0 || card.getQueue() == 1 || card.getQueue() == 3) {
				return _nextLrnIvl(card, ease);
			} else if (ease == 1) {
				// lapsed
				JSONObject conf = _lapseConf(card);
				if (conf.getJSONArray("delays").length() > 0) {
					return (int) (conf.getJSONArray("delays").getDouble(0) * 60.0);
				}
				return _nextLapseIvl(card, conf) * 86400;
			} else {
				// review
				return _nextRevIvl(card, ease) * 86400;
			}
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	private int _nextLrnIvl(Card card, int ease) {
		// this isn't easily extracted from the learn code
		if (card.getQueue() == 0) {
			card.setLeft(_startingLeft(card));
		}
		JSONObject conf = _lrnConf(card);
		try {
			if (ease == 1) {
				// fail
				return _delayForGrade(conf, conf.getJSONArray("delays").length());
			} else if (ease == 3) {
				// early removal
				return _graduatingIvl(card, conf, true, false) * 86400;
			} else {
				int left = card.getLeft() % 1000 - 1;
				if (left <= 0) {
					// graduate
					return _graduatingIvl(card, conf, false, false) * 86400;
				} else {
					return _delayForGrade(conf, left);
				}
			}
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Suspending
	 * ***************************************************************
	 * ********************************
	 */

	/**
	 * Suspend cards.
	 */
	public void suspendCards(long[] ids) {
		remFromDyn(ids);
		removeFailed(ids);
		mCol.getDb().execute("UPDATE cards SET queue = -1, mod = " + Utils.intNow()
								+ ", usn = " + mCol.usn() + " WHERE id IN "
								+ Utils.ids2str(ids));
	}

	/**
	 * Unsuspend cards
	 */
	public void unsuspendCards(long[] ids) {
		mCol.getDb().execute(
						"UPDATE cards SET queue = type, mod = "
								+ Utils.intNow() + ", usn = " + mCol.usn()
								+ " WHERE queue = -1 AND id IN "
								+ Utils.ids2str(ids));
	}

	/**
	 * Bury all cards for note until next session.
	 */
	public void buryNote(long nid) {
		mCol.setDirty();
		long[] cids = Utils.arrayList2array(mCol.getDb().queryColumn(Long.class, "SELECT id FROM cards WHERE nid = " + nid, 0));
		remFromDyn(cids);
		removeFailed(cids);
		mCol.getDb().execute("UPDATE cards SET queue = -2 WHERE nid = " + nid);
	}

	/**
	 * Counts
	 * *******************************************************************
	 * ****************************
	 */

	/** LIBANKI: not in libanki */
	public int cardCount() {
		return cardCount(_deckLimit());
	}

	public int cardCount(String dids) {
		return mCol.getDb().queryScalar(
				"SELECT count() FROM cards WHERE did IN " + dids, false);
	}

	/** LIBANKI: not in libanki */
	public int newCount() {
		return newCount(_deckLimit());
	}

	public int newCount(String dids) {
		return mCol.getDb().queryScalar(
				"SELECT count() FROM cards WHERE type = 0 AND did IN " + dids,
				false);
	}

	/** LIBANKI: not in libanki */
	public int matureCount() {
		return matureCount(_deckLimit());
	}

	public int matureCount(String dids) {
		return mCol.getDb().queryScalar(
				"SELECT count() FROM cards WHERE type = 2 AND ivl >= 21 AND did IN "
						+ dids, false);
	}

	/** NOT IN LIBANKI */
	public float todaysProgress(Card card, boolean allDecks, boolean reloadNonselected) {
		int counts = mNewCount + mLrnCount + mRevCount;
		if (card != null) {
			int idx = countIdx(card);
			if (idx == 1) {
				counts += card.getLeft();
			} else {
				counts += 1;
			}
		}
		try {
			float done = 0;
			if (allDecks) {
				if (reloadNonselected) {
					mNonselectedDues = 0;
					for (JSONObject g : mCol.getDecks().all()) {
						try {
							if (!g.getString("name").matches(".*::.*")) {
								long did = g.getLong("id");
								LinkedList<Long> ldid = new LinkedList<Long>();
								ldid.add(did);
								for (Long c : mCol.getDecks().children(did).values()) {
									ldid.add(c);
								}
								String didLimit = Utils.ids2str(ldid);
								mNonselectedDues += _walkingCount(ldid,
										Sched.class.getDeclaredMethod(
												"_deckNewLimitSingle", JSONObject.class),
										Sched.class.getDeclaredMethod("_cntFnNew",
												long.class, int.class));
								mNonselectedDues += _cntFnLrn(didLimit);
								mNonselectedDues += _walkingCount(ldid,
										Sched.class.getDeclaredMethod(
												"_deckRevLimitSingle", JSONObject.class),
										Sched.class.getDeclaredMethod("_cntFnRev",
												long.class, int.class));
							}
						} catch (JSONException e) {
							throw new RuntimeException(e);
						} catch (NoSuchMethodException e) {
							throw new RuntimeException(e);
						}
					}
					mNonselectedDues -= mNewCount + mLrnCount + mRevCount;
				}
				counts += mNonselectedDues;
				for (JSONObject d : mCol.getDecks().all()) {
					if (!d.getString("name").matches(".*::.*")) {
						done += d.getJSONArray("newToday").getInt(1) + d.getJSONArray("lrnToday").getInt(1) + d.getJSONArray("revToday").getInt(1);
					}
				}
			} else {
				JSONObject c = mCol.getDecks().current();
				done = c.getJSONArray("newToday").getInt(1) + c.getJSONArray("lrnToday").getInt(1) + c.getJSONArray("revToday").getInt(1);
			}
			return done / (done + counts);
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	public void resetTotalProgress() {
		mNonselectedDues = 0;
	}

	//
	// /**
	// * Time spent learning today, in seconds.
	// */
	// public int timeToday(int fid) {
	// return (int)
	// mDb.queryScalar("SELECT sum(taken / 1000.0) FROM revlog WHERE time > 1000 * "
	// + (mDayCutoff - 86400));
	// // TODO: check for 0?
	// }
	//
	//
	// /**
	// * Number of cards answered today.
	// */
	// public int repsToday(int fid) {
	// return (int) mDb.queryScalar("SELECT count() FROM revlog WHERE time > " +
	// (mDayCutoff - 86400));
	// }
	//
	//
	// /**
	// * Dynamic indices
	// ***********************************************************************************************
	// */
	//
	// private void updateDynamicIndices() {
	// // Log.i(AnkiDroidApp.TAG, "updateDynamicIndices - Updating indices...");
	// // // determine required columns
	// // if (mDeck.getQconf().getInt("revOrder")) {
	// //
	// // }
	// // HashMap<String, String> indices = new HashMap<String, String>();
	// // indices.put("intervalDesc", "(queue, interval desc, factId, due)");
	// // indices.put("intervalAsc", "(queue, interval, factId, due)");
	// // indices.put("randomOrder", "(queue, factId, ordinal, due)");
	// // // new cards are sorted by due, not combinedDue, so that even if
	// // // they are spaced, they retain their original sort order
	// // indices.put("dueAsc", "(queue, due, factId, due)");
	// // indices.put("dueDesc", "(queue, due desc, factId, due)");
	// //
	// // ArrayList<String> required = new ArrayList<String>();
	// // if (mRevCardOrder == REV_CARDS_OLD_FIRST) {
	// // required.add("intervalDesc");
	// // }
	// // if (mRevCardOrder == REV_CARDS_NEW_FIRST) {
	// // required.add("intervalAsc");
	// // }
	// // if (mRevCardOrder == REV_CARDS_RANDOM) {
	// // required.add("randomOrder");
	// // }
	// // if (mRevCardOrder == REV_CARDS_DUE_FIRST || mNewCardOrder ==
	// NEW_CARDS_OLD_FIRST
	// // || mNewCardOrder == NEW_CARDS_RANDOM) {
	// // required.add("dueAsc");
	// // }
	// // if (mNewCardOrder == NEW_CARDS_NEW_FIRST) {
	// // required.add("dueDesc");
	// // }
	// //
	// // // Add/delete
	// // boolean analyze = false;
	// // Set<Entry<String, String>> entries = indices.entrySet();
	// // Iterator<Entry<String, String>> iter = entries.iterator();
	// // String indexName = null;
	// // while (iter.hasNext()) {
	// // Entry<String, String> entry = iter.next();
	// // indexName = "ix_cards_" + entry.getKey();
	// // if (required.contains(entry.getKey())) {
	// // Cursor cursor = null;
	// // try {
	// // cursor = getDB().getDatabase().rawQuery(
	// // "SELECT 1 FROM sqlite_master WHERE name = '" + indexName + "'", null);
	// // if ((!cursor.moveToNext()) || (cursor.getInt(0) != 1)) {
	// // getDB().execute("CREATE INDEX " + indexName +
	// " ON cards " + entry.getValue());
	// // analyze = true;
	// // }
	// // } finally {
	// // if (cursor != null) {
	// // cursor.close();
	// // }
	// // }
	// // } else {
	// // getDB().execute("DROP INDEX IF EXISTS " + indexName);
	// // }
	// // }
	// // if (analyze) {
	// // getDB().execute("ANALYZE");
	// // }
	// }

	/**
	 * Resetting
	 * ****************************************************************
	 * *******************************
	 */

	/** Put cards at the end of the new queue. */
	public void forgetCards(long[] ids) {
		mCol.getDb().execute("update cards set type=0,queue=0,ivl=0 where id in " + Utils.ids2str(ids));
		int pmax = mCol.getDb().queryScalar("SELECT max(due) FROM cards WHERE type=0", false);
		// takes care of mod + usn
		sortCards(ids, pmax + 1);
	}

	/**
	 * Put cards in review queue with a new interval in days (min, max).
	 * @param ids The list of card ids to be affected
	 * @param imin the minimum interval (inclusive)
	 * @param imax The maximum interval (inclusive)
	 */
	public void reschedCards(long[] ids, int imin, int imax) {
	    ArrayList<Object[]> d = new ArrayList<Object[]>();
	    int t = mToday;
	    long mod = Utils.intNow();
	    Random rnd = new Random();
	    for (long id : ids) {
	        int r = rnd.nextInt(imax - imin + 1) + imin;
	        d.add(new Object[]{Math.max(1, r), r+t, mCol.usn(), mod, 2500, id});
	    }
	    mCol.getDb().executeMany("update cards set type=2,queue=2,ivl=?,due=?, " +
	        "usn=?, mod=?, factor=? where id=? and odid=0", d);
	}

	/**
	 * Repositioning new cards
	 * **************************************************
	 * *********************************************
	 */

	public void sortCards(long[] cids, int start) {
		sortCards(cids, start, 1, false, false);
	}
 	public void sortCards(long[] cids, int start, int step, boolean shuffle, boolean shift) {
		String scids = Utils.ids2str(cids);
		long now = Utils.intNow();
		ArrayList<Long> nids = mCol.getDb().queryColumn(Long.class, "SELECT DISTINCT nid FROM cards WHERE type = 0 AND id IN " + scids + " ORDER BY nid", 0);
		if (nids.size() == 0) {
			// no new cards
			return;
		}
		// determine nid ordering
		HashMap<Long, Long> due = new HashMap<Long, Long>();
		if (shuffle) {
			Collections.shuffle(nids);
		}
		for (int c = 0; c < nids.size(); c++) {
			due.put(nids.get(c), (long) (start + c * step));
		}
		int high = start + step * (nids.size() - 1);
		// shift
		if (shift) {
			int low = mCol.getDb().queryScalar("SELECT min(due) FROM cards WHERE due >= " + start + " AND type = 0 AND id NOT IN " + scids, false);
			if (low != 0) {
				int shiftby = high - low + 1;
				mCol.getDb().execute("UPDATE cards SET mod = " + now + ", usn = " + mCol.usn() + ", due = due + " + shiftby + " WHERE id NOT IN " + scids + " AND due >= " + low + " AND queue = 0");
			}
		}
		// reorder cards
		ArrayList<Object[]> d = new ArrayList<Object[]>();
		Cursor cur = null;
		try {
			cur = mCol.getDb().getDatabase().rawQuery("SELECT id, nid FROM cards WHERE type = 0 AND id IN " + scids, null);
			while (cur.moveToNext()) {
				long nid = cur.getLong(1);
				d.add(new Object[]{due.get(nid), now, mCol.usn(), cur.getLong(0)});
			}
		} finally {
			if (cur != null && !cur.isClosed()) {
				cur.close();
			}
		}
		mCol.getDb().executeMany("UPDATE cards SET due = ?, mod = ?, usn = ? WHERE id = ?", d);
	}

	public void randomizeCards(long did) {
	    List<Long> cids = mCol.getDb().queryColumn(Long.class, "select id from cards where did = " + did, 0);
	    sortCards(Utils.toPrimitive(cids), 1, 1, true, false);
	}
	
	public void orderCards(long did) {
	    List<Long> cids = mCol.getDb().queryColumn(Long.class, "select id from cards where did = " + did, 0);
	    sortCards(Utils.toPrimitive(cids), 1, 1, false, false);
	}
	
	// resortconf

	/**
	 * *************************************************************************
	 * **********************
	 */

	public String getName() {
		return mName;
	}

	public int getToday() {
		return mToday;
	}

	public long getDayCutoff() {
		return mDayCutoff;
	}

	public Collection getCol() {
		return mCol;
	}

	public int getNewCount() {
        return mNewCount;
    }

	// Needed for tests
    public LinkedList<long[]> getNewQueue() {
        return mNewQueue;
    }

    // Needed for tests
    public void setClearOverdue(boolean clearOverdue) {
        mClearOverdue = clearOverdue;
    }

    private class DeckNameCompare implements Comparator<Object[]> {
		@Override
		public int compare(Object[] lhs, Object[] rhs) {
			String[] o1 = (String[]) lhs[0];
			String[] o2 = (String[]) rhs[0];
			for (int i = 0; i < Math.min(o1.length, o2.length); i++) {
				int result = o1[i].compareToIgnoreCase(o2[i]);
				if (result != 0) {
					return result;
				}
			}
			if (o1.length < o2.length) {
				return -1;
			} else if (o1.length > o2.length) {
				return 1;
			} else {
				return 0;
			}
		}
	}

	private class DueComparator implements Comparator<long[]> {
		@Override
		public int compare(long[] lhs, long[] rhs) {
			return new Long(lhs[0]).compareTo(rhs[0]);
		}
	}

}
