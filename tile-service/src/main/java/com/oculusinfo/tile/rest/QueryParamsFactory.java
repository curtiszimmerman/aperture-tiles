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
package com.oculusinfo.tile.rest;

import java.util.List;
import java.util.Properties;

import org.json.JSONObject;

import com.oculusinfo.factory.ConfigurableFactory;
import com.oculusinfo.factory.ConfigurationException;
import com.oculusinfo.factory.ConfigurationProperty;
import com.oculusinfo.factory.properties.JSONProperty;

/**
 * This {@link ConfigurableFactory} is meant to create QueryParam objects that keep
 * track of the key/value pairs passed through a data request like 'GET'. All
 * parameters passed to this object through {@link #readConfiguration(JSONObject)}
 * will be treated as query parameters. This makes it easy to pass in a configuration
 * that has a 'query' path containing all of the values for the query.
 *  
 * <pre><code>
 * JSONObject configData = new JSONObject();
 * configData.put("query", resource.getRequest().getResourceRef().getQueryAsForm().getValuesMap());
 * 
 * QueryParamsFactory configFactory = new QueryParamsFactory(null, null, Collections.singletonList("query"));
 * configFactory.readConfiguration(configData);
 * </code></pre>
 * 
 * @author cregnier
 *
 */
public class QueryParamsFactory extends ConfigurableFactory<QueryParams> {

	public static JSONProperty QUERY_PARAMS = new JSONProperty("query", "The key/value pairs passed from a 'GET' query", null);
	
	public QueryParamsFactory(ConfigurableFactory<?> parent, List<String> path) {
		this(null, parent, path);
	}
	
	public QueryParamsFactory(String factoryName, ConfigurableFactory<?> parent, List<String> path) {
		super(factoryName, QueryParams.class, parent, path);
		addProperty(QUERY_PARAMS);
	}
	
	@Override
	protected QueryParams create() {
		return new QueryParamsImpl(getPropertyValue(QUERY_PARAMS));
	}

	@Override
	public void readConfiguration(JSONObject rootNode) throws ConfigurationException {
		// TODO Auto-generated method stub
		super.readConfiguration(rootNode);
	}
	
	@Override
	public void readConfiguration(Properties properties) throws ConfigurationException {
		// TODO Auto-generated method stub
		super.readConfiguration(properties);
	}

	@Override
	protected <PT> void readProperty(JSONObject factoryNode, ConfigurationProperty<PT> property) throws ConfigurationException {
		//we only care about the QUERY_PARAMS property, and we want to store all the properties that were passed to us
		if (QUERY_PARAMS.equals(property)) {
			setPropertyValue(QUERY_PARAMS, factoryNode);
		}
	}
}
