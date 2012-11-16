package org.mit.jstreamit;

/**
 * Represents a rate declaration, with min, max and average values.
 *
 * Rates can be "partially dynamic", in which the maximum rate is known but
 * either does not equal the minimum rate or the minimum rate is dynamic, or
 * "fully dynamic", in which the minimum rate may or may not be known but the
 * maximum rate is dynamic. ("Partially dynamic" means there exists some buffer
 * size for which the filter can definitely be fired; "fully dynamic" means you
 * can't do any better than trying no matter how much you've buffered.)
 * TODO: output rates want partially dynamic to mean a lower bound?
 *
 * Note that the average rate may be dynamic even if the minimum and maximum
 * rates are known (so long as they aren't equal).
 *
 * Zero rates are legal, representing sources or sinks whose processing is
 * completed by their side effects.
 *
 * Instances of this class are immutable.
 *
 * @author Jeffrey Bosboom <jeffreybosboom@gmail.com>
 * @since 11/16/2012
 */
public class Rate {
	public static final int DYNAMIC = -1;
	private final int min, max, avg;

	/**
	 * Creates a new Rate object.  This constructor is private to funnel all
	 * constructor through the create() functions, allowing for caching of Rate
	 * objects.
	 * @param min the minimum rate
	 * @param max the maximum rate
	 * @param avg the average rate
	 */
	private Rate(int min, int max, int avg) {
		if (min < 0 && min != DYNAMIC)
			throwIAE(min, max, avg);
		if (max < 0 && max != DYNAMIC)
			throwIAE(min, max, avg);
		if (!(min <= max))
			throwIAE(min, max, avg);
		if (min == max && min != DYNAMIC && avg != min)
			throwIAE(min, max, avg);
		if (min != DYNAMIC && max != DYNAMIC && avg != DYNAMIC && (avg < min || avg > max))
			throwIAE(min, max, avg);
		this.min = min;
		this.max = max;
		this.avg = avg;
	}

	/**
	 * Throws a nicely formatted IllegalArgumentException.
	 * TODO: should this throw its own exception subclass to allow programmatic
	 * access to min/max/avg?
	 */
	private static void throwIAE(int min, int max, int avg) {
		throw new IllegalArgumentException(
				String.format("Illegal rate: [%s, %s, %s]",
				stringizeRate(min),
				stringizeRate(max),
				stringizeRate(avg)));
	}

	/**
	 * Creates a Rate object with minimum, maximum and average all set to the
	 * given value (possibly dynamic).  This is the common case.
	 * @param value the rate
	 */
	public static Rate create(int value) {
		return new Rate(value, value, value);
	}

	/**
	 * Creates a Rate object with the given minimum and maximum rates (possibly
	 * dynamic).  The average rate will be dynamic unless min == max, in which
	 * case it will be min.
	 * @param min the minimum rate
	 * @param max the maximum rate
	 */
	public static Rate create(int min, int max) {
		if (min == max)
			return new Rate(min, max, min);
		else
			return new Rate(min, max, DYNAMIC);
	}

	/**
	 * Creates a Rate object with the given minimum, maximum and average rates.
	 * @param min the minimum rate
	 * @param max the maximum rate
	 * @param avg the average rate
	 */
	public static Rate create(int min, int max, int avg) {
		return new Rate(min, max, avg);
	}

	/**
	 * Returns the minimum rate.
	 * @return the minimum rate
	 */
	public int min() {
		return min;
	}

	/**
	 * Returns the maximum rate.
	 * @return the maximum rate
	 */
	public int max() {
		return max;
	}

	/**
	 * Returns the average rate.  Note that the average rate may be dynamic
	 * even if the minimum and maximum rates are both known.
	 * @return the average rate
	 */
	public int avg() {
		return avg;
	}

	/**
	 * Returns true iff this rate is partially dynamic (known maximum rate but
	 * dynamic or lower known minimum rate).
	 * @return true iff this rate is partially dynamic
	 */
	public boolean isPartiallyDynamic() {
		return max() != DYNAMIC && (min() == DYNAMIC || min() < max());
	}

	/**
	 * Returns true iff this rate is fully dynamic (TODO: meaning)
	 * @return
	 */
	public boolean isFullyDynamic() {
		return max() == DYNAMIC;
	}

	/**
	 * Returns true iff this rate is dynamic (either partially or fully)
	 * @return true iff this rate is dynamic
	 */
	public boolean isDynamic() {
		return isPartiallyDynamic() || isFullyDynamic();
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final Rate other = (Rate)obj;
		if (this.min != other.min)
			return false;
		if (this.max != other.max)
			return false;
		if (this.avg != other.avg)
			return false;
		return true;
	}

	@Override
	public int hashCode() {
		int hash = 5;
		hash = 41 * hash + this.min;
		hash = 41 * hash + this.max;
		hash = 41 * hash + this.avg;
		return hash;
	}

	@Override
	public String toString() {
		return String.format("[%s, %s, %s]",
				stringizeRate(min),
				stringizeRate(max),
				stringizeRate(avg));
	}

	/**
	 * Converts the given rate value to a String: "*" if dynamic, the decimal
	 * representation otherwise.
	 * @param x a rate value to stringize
	 * @return "*" if dynamic, the decimal representation of x otherwise
	 */
	private static String stringizeRate(int x) {
		return x == DYNAMIC ? "*" : Integer.toString(x);
	}
}
