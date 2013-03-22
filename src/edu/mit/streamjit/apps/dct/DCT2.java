/**
 * @author Sumanan sumanan@mit.edu
 * @since Mar 13, 2013
 * Moved from StreamIt's ALPLOS06 benchmark
 */

/*
 * Copyright 2005 by the Massachusetts Institute of Technology.
 *
 * Permission to use, copy, modify, and distribute this
 * software and its documentation for any purpose and without
 * fee is hereby granted, provided that the above copyright
 * notice appear in all copies and that both that copyright
 * notice and this permission notice appear in supporting
 * documentation, and that the name of M.I.T. not be used in
 * advertising or publicity pertaining to distribution of the
 * software without specific, written prior permission.
 * M.I.T. makes no representations about the suitability of
 * this software for any purpose.  It is provided "as is"
 * without express or implied warranty.
 */

/**
 * @description
 * This file contains functions that implement Discrete Cosine Transforms and
 * their inverses.  When reference is made to the IEEE DCT specification, it
 * is refering to the IEEE DCT specification used by both MPEG and JPEG.
 * A definition of what makes an 8x8 DCT conform to the IEEE specification, as well
 * as a pseudocode implementation, can be found in Appendix A of the MPEG-2 specification
 * (ISO/IEC 13818-2) on P. 125. 
 */

package edu.mit.streamjit.apps.dct;

import edu.mit.streamjit.api.CompiledStream;
import edu.mit.streamjit.api.Filter;
import edu.mit.streamjit.api.Pipeline;
import edu.mit.streamjit.api.RoundrobinJoiner;
import edu.mit.streamjit.api.RoundrobinSplitter;
import edu.mit.streamjit.api.Splitjoin;
import edu.mit.streamjit.api.StreamCompiler;
import edu.mit.streamjit.impl.interp.DebugStreamCompiler;

public class DCT2 {
	public static void main(String[] args) throws InterruptedException {
		DCT2Kernel kernel = new DCT2Kernel();
		StreamCompiler sc = new DebugStreamCompiler();
		CompiledStream<Integer, Integer> stream = sc.compile(kernel);
		Integer output;
		for (int i = 0; i < 100000; i++) {
			stream.offer(i);	
			while ((output = stream.poll()) != null)
				System.out.println(output);
		}
		
		stream.drain();
		stream.awaitDraining();

	}

	private static class DCT2Kernel extends Pipeline<Integer, Integer> {
		DCT2Kernel(){
	   // add FileReader<int>("../input/idct-input-small.bin"); // FIXME
	    add(new iDCT8x8_ieee(16));
	  //  add FileWriter<int>("idct-output2.bin");	//FIXME
		}
	}

	/**
	 * Transforms an 8x8 signal from the frequency domain to the signal domain
	 * using an inverse Discrete Cosine Transform in accordance with the IEEE
	 * specification for a 2-dimensional 8x8 iDCT.
	 * 
	 * @input 64 values representing an 8x8 array of values in the frequency
	 *        domain, ordered by row and then column. Vertical frequency
	 *        increases along each row and horizontal frequency along each
	 *        column.
	 * @output 64 values representing an 8x8 array of values in the signal
	 *         domain, ordered by row and then column.
	 * @param mode
	 *            indicates algorithm to use; mode == 0: reference, coarse
	 *            implementation mode == 1: reference, fine (parallel)
	 *            implementation mode == 2: fast, coarse implementation mode ==
	 *            3: fast, fine (parallel) implementation
	 */
	private static class iDCT8x8_ieee extends Pipeline<Integer, Integer> {
		iDCT8x8_ieee(int x) {
			add(new iDCT_2D_reference_fine(x));
		}
	}

	/**
	 * Transforms an 8x8 signal from the signal domain to the frequency domain
	 * using a Discrete Cosine Transform in accordance with the IEEE
	 * specification for a 2-dimensional 8x8 DCT.
	 * 
	 * @input 64 values representing an 8x8 array of values in the signal
	 *        domain, ordered by row and then column.
	 * @output 64 values representing an 8x8 array of values in the frequency
	 *         domain, ordered by row and then column. Vertical frequency
	 *         increases along each row and horizontal frequency along each
	 *         column.
	 * @param mode
	 *            indicates algorithm to use; mode == 0: reference, coarse
	 *            implementation, mode == 1: reference, fine (parallel)
	 *            implementation
	 */
	private static class DCT8x8_ieee extends Pipeline<Integer, Integer> {
		DCT8x8_ieee(int mode) {
			// modes:
			// 0: reference, coarse
			// 1: reference, fine (parallel)
			if (mode == 0)
				add(new DCT_2D_reference_coarse(8));
			else
				add(new DCT_2D_reference_fine(8));
		}
	}

	/**
	 * Transforms a 2D signal from the frequency domain to the signal domain
	 * using an inverse Discrete Cosine Transform.
	 * 
	 * @param size
	 *            The number of elements in each dimension of the signal.
	 * @input size x size values, representing an array of values in the
	 *        frequency domain, ordered by row and then column. Vertical
	 *        frequency increases along each row and horizontal frequency along
	 *        each column.
	 * @output size x size values representing an array of values in the signal
	 *         domain, ordered by row and then column.
	 */
	private static class iDCT_2D_reference_fine extends
			Pipeline<Integer, Integer> {
		iDCT_2D_reference_fine(int size) {

			add(new IntToFloat());
			add(new iDCT_1D_Y_reference_fine(size));
			add(new iDCT_1D_X_reference_fine(size));
			add(new FloatToInt());
		}
	}

	private static class IntToFloat extends Filter<Integer, Float> {

		public IntToFloat() {
			super(1, 1);
		}

		@Override
		public void work() {
			push((float) pop());
		}
	}

	private static class FloatToInt extends Filter<Float, Integer> {

		public FloatToInt() {
			super(1, 1);
		}

		@Override
		public void work() {
			push(pop().intValue());
		}
	}

	/**
	 * Transforms a 2D signal from the frequency domain to the signal domain
	 * using a FAST inverse Discrete Cosine Transform.
	 * 
	 * @input size x size values, representing an array of values in the
	 *        frequency domain, ordered by row and then column. Vertical
	 *        frequency increases along each row and horizontal frequency along
	 *        each column.
	 * @output size x size values representing an array of values in the signal
	 *         domain, ordered by row and then column.
	 */
	private static class iDCT8x8_2D_fast_coarse extends
			Pipeline<Integer, Integer> {
		iDCT8x8_2D_fast_coarse() {
			add(new iDCT8x8_1D_row_fast());
			add(new iDCT8x8_1D_col_fast());
		}
	}

	/**
	 * Transforms a 2D signal from the frequency domain to the signal domain
	 * using a FAST inverse Discrete Cosine Transform.
	 * 
	 * @input size x size values, representing an array of values in the
	 *        frequency domain, ordered by row and then column. Vertical
	 *        frequency increases along each row and horizontal frequency along
	 *        each column.
	 * @output size x size values representing an array of values in the signal
	 *         domain, ordered by row and then column.
	 */
	private static class iDCT8x8_2D_fast_fine extends
			Pipeline<Integer, Integer> {
		iDCT8x8_2D_fast_fine() {
			add(new iDCT8x8_1D_X_fast_fine());
			add(new iDCT8x8_1D_Y_fast_fine());
		}
	}

	/**
	 * Transforms a 2D signal from the signal domain to the frequency domain
	 * using a Discrete Cosine Transform.
	 * 
	 * @param size
	 *            The number of elements in each dimension of the signal.
	 * @input size x size values, representing an array of values in the signal
	 *        domain, ordered by row and then column.
	 * @output size x size values representing an array of values in the
	 *         frequency domain, ordered by row and then column. Vertical
	 *         frequency increases along each row and horizontal frequency along
	 *         each column.
	 */
	private static class DCT_2D_reference_fine extends
			Pipeline<Integer, Integer> {
		DCT_2D_reference_fine(int size){
	    add (new IntToFloat());
	    add (new DCT_1D_X_reference_fine(size));
	    add (new DCT_1D_Y_reference_fine(size));
	    add (new FloatToInt());
		}
	}

	/**
	 * @internal
	 */
	private static class iDCT_1D_X_reference_fine extends
			Splitjoin<Float, Float> {
		iDCT_1D_X_reference_fine(int size){
	    super(new RoundrobinSplitter<Float>(size), new RoundrobinJoiner<Float>(size));
	    for (int i = 0; i < size; i++) {
	        add ( new iDCT_1D_reference_fine(size)); 
	    }
		}
	}

	/**
	 * @internal
	 */
	private static class iDCT_1D_Y_reference_fine extends
			Splitjoin<Float, Float> {
		iDCT_1D_Y_reference_fine(int size) {
			super(new RoundrobinSplitter<Float>(1),
					new RoundrobinJoiner<Float>(1));
			for (int i = 0; i < size; i++) {
				add(new iDCT_1D_reference_fine(size));
			}
		}
	}

	/**
	 * @internal
	 */
	private static class iDCT8x8_1D_X_fast_fine extends
			Splitjoin<Integer, Integer> {
		iDCT8x8_1D_X_fast_fine() {
			super(new RoundrobinSplitter<Integer>(8),
					new RoundrobinJoiner<Integer>(8));
			for (int i = 0; i < 8; i++) {
				add(new iDCT8x8_1D_row_fast());
			}
		}
	}

	/**
	 * @internal
	 */
	private static class iDCT8x8_1D_Y_fast_fine extends
			Splitjoin<Integer, Integer> {
		iDCT8x8_1D_Y_fast_fine() {
			super(new RoundrobinSplitter<Integer>(1),
					new RoundrobinJoiner<Integer>(1));
			for (int i = 0; i < 8; i++) {
				add(new iDCT8x8_1D_col_fast_fine());
			}
		}
	}

	/**
	 * @internal
	 */
	private static class DCT_1D_X_reference_fine extends
			Splitjoin<Float, Float> {
		DCT_1D_X_reference_fine(int size) {
			super(new RoundrobinSplitter<Float>(size),
					new RoundrobinJoiner<Float>(size));
			for (int i = 0; i < size; i++) {
				add(new DCT_1D_reference_fine(size));
			}
		}
	}

	/**
	 * @internal
	 */
	private static class DCT_1D_Y_reference_fine extends
			Splitjoin<Float, Float> {
		DCT_1D_Y_reference_fine(int size) {
			super(new RoundrobinSplitter<Float>(1),
					new RoundrobinJoiner<Float>(1));
			for (int i = 0; i < size; i++) {
				add(new DCT_1D_reference_fine(size));
			}
		}
	}

	/**
	 * @internal Based on the implementation given in the C MPEG-2 reference
	 *           implementation
	 */
	private static class iDCT_2D_reference_coarse extends
			Filter<Integer, Integer> {
		int size;
		float[][] coeff;

		iDCT_2D_reference_coarse(int size) {
			super(size * size, size * size, size * size);
			coeff = new float[size][size];
			init();
		}

		private void init() {
			for (int freq = 0; freq < size; freq++) {
				float scale = (float) ((freq == 0) ? Math.sqrt(0.125) : 0.5);
				for (int time = 0; time < size; time++)
					coeff[freq][time] = (float) (scale * Math
							.cos((Math.PI / (float) size) * freq * (time + 0.5)));
			}
		}

		public void work() {
			float[][] block_x = new float[size][size];
			int i, j, k;

			for (i = 0; i < size; i++)
				for (j = 0; j < size; j++) {
					block_x[i][j] = 0;
					for (k = 0; k < size; k++) {
						block_x[i][j] += coeff[k][j] * peek(size * i + k /*
																		 * that
																		 * is
																		 * buffer
																		 * [
																		 * i][k]
																		 */);
					}
				}

			for (i = 0; i < size; i++) {
				for (j = 0; j < size; j++) {
					float block_y = 0.0f;
					for (k = 0; k < size; k++) {
						block_y += coeff[k][i] * block_x[k][j];
					}
					block_y = (float) Math.floor(block_y + 0.5);
					push((int) block_y);
				}
			}

			for (i = 0; i < size * size; i++)
				pop();
		}
	}

	/**
	 * Transforms a 2D signal from the signal domain to the frequency domain
	 * using a Discrete Cosine Transform.
	 * 
	 * @param size
	 *            The number of elements in each dimension of the signal.
	 * @input size values, representing an array of values in the signal domain,
	 *        ordered by row and then column.
	 * @output size values representing an array of values in the frequency
	 *         domain, ordered by row and then column. Vertical frequency
	 *         increases along each row and horizontal frequency along each
	 *         column.
	 */
	private static class DCT_2D_reference_coarse extends
			Filter<Integer, Integer> {
		float[][] coeff;
		int size;

		DCT_2D_reference_coarse(int size) {
			super(size * size, size * size, size * size);
			this.size = size;
			coeff = new float[size][size];
			init();
		}

		private void init() {
			for (int i = 0; i < size; i++) {
				float s = (float) ((i == 0) ? Math.sqrt(0.125) : 0.5);
				for (int j = 0; j < size; j++)
					coeff[i][j] = (float) (s * Math.cos((Math.PI / size) * i
							* (j + 0.5)));
			}
		}

		public void work() {
			float[][] block_x = new float[size][size];
			int i, j, k;

			for (i = 0; i < size; i++)
				for (j = 0; j < size; j++) {
					block_x[i][j] = 0.0f;
					for (k = 0; k < size; k++) {
						block_x[i][j] += coeff[j][k] * peek(size * i + k);
					}
				}

			for (i = 0; i < size; i++) {
				for (j = 0; j < size; j++) {
					float block_y = 0.0f;
					for (k = 0; k < size; k++) {
						block_y += coeff[i][k] * block_x[k][j];
					}
					block_y = (float) Math.floor(block_y + 0.5);
					push((int) block_y);
				}
			}

			for (i = 0; i < size * size; i++)
				pop();
		}
	}

	/**
	 * Transforms a 1D signal from the frequency domain to the signal domain
	 * using an inverse Discrete Cosine Transform.
	 * 
	 * @param size
	 *            The number of elements in each dimension of the signal.
	 * @input size values, representing an array of values in the frequency
	 *        domain, ordered by row and then column. Vertical frequency
	 *        increases along each row and horizontal frequency along each
	 *        column.
	 * @output size values representing an array of values in the signal domain,
	 *         ordered by row and then column.
	 */
	private static class iDCT_1D_reference_fine extends Filter<Float, Float> {
		float[][] coeff;
		int size;

		iDCT_1D_reference_fine(int size) {
			super(size, size, size);
			this.size = size;
			coeff = new float[size][size];
			init();
		}

		private void init() {
			for (int x = 0; x < size; x++) {
				for (int u = 0; u < size; u++) {
					float Cu = 1;
					if (u == 0)
						Cu = (float) (1 / Math.sqrt(2));
					coeff[x][u] = (float) (0.5 * Cu * Math.cos(u * Math.PI
							* (2.0 * x + 1) / (2.0 * size)));
				}
			}
		}

		public void work() {
			for (int x = 0; x < size; x++) {
				float tempsum = 0;
				for (int u = 0; u < size; u++) {
					tempsum += coeff[x][u] * peek(u);
				}
				push(tempsum);
			}
			// added by mgordon
			for (int i = 0; i < size; i++)
				pop();
		}
	}

	/**
	 * Transforms a 1D horizontal signal from the frequency domain to the signal
	 * domain using a FAST inverse Discrete Cosine Transform.
	 * 
	 * @input size values, representing an array of values in the frequency
	 *        domain, ordered by row and then column. Vertical frequency
	 *        increases along each row and horizontal frequency along each
	 *        column.
	 * @output size values representing an array of values in the signal domain,
	 *         ordered by row and then column.
	 */
	private static class iDCT8x8_1D_row_fast extends Filter<Integer, Integer> {
		private static int size = 8;

		int W1 = 2841; /* 2048*sqrt(2)*cos(1*pi/16) */
		int W2 = 2676; /* 2048*sqrt(2)*cos(2*pi/16) */
		int W3 = 2408; /* 2048*sqrt(2)*cos(3*pi/16) */
		int W5 = 1609; /* 2048*sqrt(2)*cos(5*pi/16) */
		int W6 = 1108; /* 2048*sqrt(2)*cos(6*pi/16) */
		int W7 = 565; /* 2048*sqrt(2)*cos(7*pi/16) */

		iDCT8x8_1D_row_fast() {
			super(size, size, size);
		}

		public void work() {
			int x0 = peek(0);
			int x1 = peek(4) << 11;
			int x2 = peek(6);
			int x3 = peek(2);
			int x4 = peek(1);
			int x5 = peek(7);
			int x6 = peek(5);
			int x7 = peek(3);
			int x8;

			/* shortcut */
			if ((x1 == 0) && (x2 == 0) && (x3 == 0) && (x4 == 0) && (x5 == 0)
					&& (x6 == 0) && (x7 == 0)) {
				x0 = x0 << 3;
				for (int i = 0; i < size; i++) {
					push(x0);
				}
			} else {
				/* for proper rounding in the fourth stage */
				x0 = (x0 << 11) + 128;

				/* first stage */
				x8 = W7 * (x4 + x5);
				x4 = x8 + (W1 - W7) * x4;
				x5 = x8 - (W1 + W7) * x5;
				x8 = W3 * (x6 + x7);
				x6 = x8 - (W3 - W5) * x6;
				x7 = x8 - (W3 + W5) * x7;

				/* second stage */
				x8 = x0 + x1;
				x0 = x0 - x1;
				x1 = W6 * (x3 + x2);
				x2 = x1 - (W2 + W6) * x2;
				x3 = x1 + (W2 - W6) * x3;
				x1 = x4 + x6;
				x4 = x4 - x6;
				x6 = x5 + x7;
				x5 = x5 - x7;

				/* third stage */
				x7 = x8 + x3;
				x8 = x8 - x3;
				x3 = x0 + x2;
				x0 = x0 - x2;
				x2 = (181 * (x4 + x5) + 128) >> 8;
				x4 = (181 * (x4 - x5) + 128) >> 8;

				/* fourth stage */
				push((x7 + x1) >> 8);
				push((x3 + x2) >> 8);
				push((x0 + x4) >> 8);
				push((x8 + x6) >> 8);
				push((x8 - x6) >> 8);
				push((x0 - x4) >> 8);
				push((x3 - x2) >> 8);
				push((x7 - x1) >> 8);
			}
			for (int i = 0; i < size; i++)
				pop();
		}
	}

	/**
	 * Transforms a 1D vertical signal from the frequency domain to the signal
	 * domain using a FAST inverse Discrete Cosine Transform.
	 * 
	 * @input size*size values, representing an array of values in the frequency
	 *        domain, ordered by row and then column. Vertical frequency
	 *        increases along each row and horizontal frequency along each
	 *        column.
	 * @output size values representing an array of values in the signal domain,
	 *         ordered by row and then column.
	 */
	private static class iDCT8x8_1D_col_fast extends Filter<Integer, Integer> {
		static int size = 8;
		int[] buffer;

		int W1 = 2841; /* 2048*sqrt(2)*cos(1*pi/16) */
		int W2 = 2676; /* 2048*sqrt(2)*cos(2*pi/16) */
		int W3 = 2408; /* 2048*sqrt(2)*cos(3*pi/16) */
		int W5 = 1609; /* 2048*sqrt(2)*cos(5*pi/16) */
		int W6 = 1108; /* 2048*sqrt(2)*cos(6*pi/16) */
		int W7 = 565; /* 2048*sqrt(2)*cos(7*pi/16) */

		iDCT8x8_1D_col_fast() {
			super(size * size, size * size, size * size);
			buffer = new int[size * size];
		}
		
		public void work() {
			for (int c = 0; c < size; c++) {
				int x0 = peek(c + size * 0);
				int x1 = peek(c + size * 4) << 8;
				int x2 = peek(c + size * 6);
				int x3 = peek(c + size * 2);
				int x4 = peek(c + size * 1);
				int x5 = peek(c + size * 7);
				int x6 = peek(c + size * 5);
				int x7 = peek(c + size * 3);
				int x8;

				/* shortcut */
				if ((x1 == 0) && (x2 == 0) && (x3 == 0) && (x4 == 0)
						&& (x5 == 0) && (x6 == 0) && (x7 == 0)) {
					x0 = (x0 + 32) >> 6;
					for (int i = 0; i < size; i++) {
						buffer[c + size * i] = x0;
					}
				} else {
					/* for proper rounding in the fourth stage */
					x0 = (x0 << 8) + 8192;

					/* first stage */
					x8 = W7 * (x4 + x5) + 4;
					x4 = (x8 + (W1 - W7) * x4) >> 3;
					x5 = (x8 - (W1 + W7) * x5) >> 3;
					x8 = W3 * (x6 + x7) + 4;
					x6 = (x8 - (W3 - W5) * x6) >> 3;
					x7 = (x8 - (W3 + W5) * x7) >> 3;

					/* second stage */
					x8 = x0 + x1;
					x0 = x0 - x1;
					x1 = W6 * (x3 + x2) + 4;
					x2 = (x1 - (W2 + W6) * x2) >> 3;
					x3 = (x1 + (W2 - W6) * x3) >> 3;
					x1 = x4 + x6;
					x4 = x4 - x6;
					x6 = x5 + x7;
					x5 = x5 - x7;

					/* third stage */
					x7 = x8 + x3;
					x8 = x8 - x3;
					x3 = x0 + x2;
					x0 = x0 - x2;
					x2 = (181 * (x4 + x5) + 128) >> 8;
					x4 = (181 * (x4 - x5) + 128) >> 8;

					/* fourth stage */
					buffer[c + size * 0] = ((x7 + x1) >> 14);
					buffer[c + size * 1] = ((x3 + x2) >> 14);
					buffer[c + size * 2] = ((x0 + x4) >> 14);
					buffer[c + size * 3] = ((x8 + x6) >> 14);
					buffer[c + size * 4] = ((x8 - x6) >> 14);
					buffer[c + size * 5] = ((x0 - x4) >> 14);
					buffer[c + size * 6] = ((x3 - x2) >> 14);
					buffer[c + size * 7] = ((x7 - x1) >> 14);
				}
			}
			for (int i = 0; i < size * size; i++) {
				pop();
				push(buffer[i]);
			}
		}
	}

	/**
	 * Transforms a 1D vertical signal from the frequency domain to the signal
	 * domain using a FAST inverse Discrete Cosine Transform.
	 * 
	 * @param size
	 *            The number of elements in each dimension of the signal.
	 * @input size values, representing an array of values in the frequency
	 *        domain, ordered by row and then column. Vertical frequency
	 *        increases along each row and horizontal frequency along each
	 *        column.
	 * @output size values representing an array of values in the signal domain,
	 *         ordered by row and then column.
	 */
	private static class iDCT8x8_1D_col_fast_fine extends
			Filter<Integer, Integer> {
		static int size = 8;
		int W1 = 2841; /* 2048*sqrt(2)*cos(1*pi/16) */
		int W2 = 2676; /* 2048*sqrt(2)*cos(2*pi/16) */
		int W3 = 2408; /* 2048*sqrt(2)*cos(3*pi/16) */
		int W5 = 1609; /* 2048*sqrt(2)*cos(5*pi/16) */
		int W6 = 1108; /* 2048*sqrt(2)*cos(6*pi/16) */
		int W7 = 565; /* 2048*sqrt(2)*cos(7*pi/16) */

		iDCT8x8_1D_col_fast_fine() {
			super(size, size);

		}

		public void work() {
			int x0 = peek(0);
			int x1 = peek(4) << 8;
			int x2 = peek(6);
			int x3 = peek(2);
			int x4 = peek(1);
			int x5 = peek(7);
			int x6 = peek(5);
			int x7 = peek(3);
			int x8;

			/* shortcut */
			if ((x1 == 0) && (x2 == 0) && (x3 == 0) && (x4 == 0) && (x5 == 0)
					&& (x6 == 0) && (x7 == 0)) {
				x0 = (x0 + 32) >> 6;
				for (int i = 0; i < size; i++) {
					push(x0);
				}
			} else {
				/* for proper rounding in the fourth stage */
				x0 = (x0 << 8) + 8192;

				/* first stage */
				x8 = W7 * (x4 + x5) + 4;
				x4 = (x8 + (W1 - W7) * x4) >> 3;
				x5 = (x8 - (W1 + W7) * x5) >> 3;
				x8 = W3 * (x6 + x7) + 4;
				x6 = (x8 - (W3 - W5) * x6) >> 3;
				x7 = (x8 - (W3 + W5) * x7) >> 3;

				/* second stage */
				x8 = x0 + x1;
				x0 = x0 - x1;
				x1 = W6 * (x3 + x2) + 4;
				x2 = (x1 - (W2 + W6) * x2) >> 3;
				x3 = (x1 + (W2 - W6) * x3) >> 3;
				x1 = x4 + x6;
				x4 = x4 - x6;
				x6 = x5 + x7;
				x5 = x5 - x7;

				/* third stage */
				x7 = x8 + x3;
				x8 = x8 - x3;
				x3 = x0 + x2;
				x0 = x0 - x2;
				x2 = (181 * (x4 + x5) + 128) >> 8;
				x4 = (181 * (x4 - x5) + 128) >> 8;

				/* fourth stage */
				push((x7 + x1) >> 14);
				push((x3 + x2) >> 14);
				push((x0 + x4) >> 14);
				push((x8 + x6) >> 14);
				push((x8 - x6) >> 14);
				push((x0 - x4) >> 14);
				push((x3 - x2) >> 14);
				push((x7 - x1) >> 14);
			}
			for (int i = 0; i < size; i++)
				pop();
		}
	}

	/**
	 * Transforms a 1D signal from the signal domain to the frequency domain
	 * using a Discrete Cosine Transform.
	 * 
	 * @param size
	 *            The number of elements in each dimension of the signal.
	 * @input size values, representing an array of values in the signal domain,
	 *        ordered by row and then column.
	 * @output size values representing an array of values in the frequency
	 *         domain, ordered by row and then column. Vertical frequency
	 *         increases along each row and horizontal frequency along each
	 *         column.
	 */
	private static class DCT_1D_reference_fine extends Filter<Float, Float> {
		float[][] coeff;
		int size;

		DCT_1D_reference_fine(int size) {
			super(size, size, size);
			this.size = size;
			coeff = new float[size][size];
			init();
		}

		private void init() {
			for (int u = 0; u < size; u++) {
				float Cu = 1;
				if (u == 0)
					Cu = (float) (1 / Math.sqrt(2));

				for (int x = 0; x < size; x++) {
					coeff[u][x] = (float) (0.5 * Cu * Math.cos(u * Math.PI
							* (2.0 * x + 1) / (2.0 * size)));
				}
			}
		}

		public void work() {
			for (int u = 0; u < size; u++) {
				float tempsum = 0;
				for (int x = 0; x < size; x++) {
					tempsum += peek(x) * coeff[u][x];
				}
				push(tempsum);
			}
		}
	}
}