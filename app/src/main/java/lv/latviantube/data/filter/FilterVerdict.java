package lv.latviantube.data.filter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of running a video through the Latvian language gate.
 *
 * <p>A verdict is never just a boolean. Every point of the score is attributed
 * to a named {@link Signal}, so the UI can explain <em>why</em> a video was
 * admitted and so a wrong decision can be traced to the signal that caused it
 * rather than guessed at.
 */
public final class FilterVerdict {

    /** One contributing piece of evidence. */
    public static final class Signal {
        private final String mId;
        private final String mLabel;
        private final int mWeight;

        public Signal(String id, String label, int weight) {
            mId = id;
            mLabel = label;
            mWeight = weight;
        }

        public String getId() {
            return mId;
        }

        public String getLabel() {
            return mLabel;
        }

        public int getWeight() {
            return mWeight;
        }

        @Override
        public String toString() {
            return mLabel + " (" + (mWeight >= 0 ? "+" : "") + mWeight + ")";
        }
    }

    private final int mScore;
    private final boolean mAccepted;
    private final List<Signal> mSignals;
    private final boolean mDecisive;

    FilterVerdict(int score, boolean accepted, List<Signal> signals, boolean decisive) {
        mScore = score;
        mAccepted = accepted;
        mSignals = Collections.unmodifiableList(new ArrayList<>(signals));
        mDecisive = decisive;
    }

    public int getScore() {
        return mScore;
    }

    public boolean isAccepted() {
        return mAccepted;
    }

    public List<Signal> getSignals() {
        return mSignals;
    }

    /**
     * True when YouTube itself supplied a language tag, so the verdict rests on
     * stated metadata rather than inference. In practice this is rare: probed
     * player responses omitted {@code defaultAudioLanguage} entirely.
     */
    public boolean isDecisive() {
        return mDecisive;
    }

    /** A short, human-readable reason, suitable for a card badge. */
    public String explain() {
        if (mSignals.isEmpty()) {
            return "Nav latviešu valodas pazīmju";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mSignals.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(mSignals.get(i).getLabel());
        }
        return sb.toString();
    }
}
