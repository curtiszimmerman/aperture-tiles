/*
 * Copyright (c) 2014 Oculus Info Inc.
 * http://www.oculusinfo.com/
 *
 * Released under the MIT License.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:

 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.

 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oculusinfo.binning.io;

import java.util.List;

import com.oculusinfo.factory.ConfigurableFactory;

/**
 * A helper factory to enable getting data from data paths within the configuration phase.<br>
 * For example, if all configuration data is under some root path, then this factory
 * can be added as the root of the configurable hierarchy to give everyone a different path,
 * without having to modify the path values for each factory directly. This factory doesn't create anything.
 * 
 * @author cregnier
 *
 */
public class EmptyConfigurableFactory extends ConfigurableFactory<Object> {

	public EmptyConfigurableFactory(String name, ConfigurableFactory<?> parent, List<String> path) {
		super(name, Object.class, parent, path);
	}

	@Override
	protected Object create() {
		return new Object();
	}

	/**
	 * Overridden in order to make this public so others can compose trees
	 */
	@Override
	public void addChildFactory(ConfigurableFactory<?> child) {
		super.addChildFactory(child);
	}
}
