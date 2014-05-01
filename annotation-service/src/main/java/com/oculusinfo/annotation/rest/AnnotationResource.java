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
package com.oculusinfo.annotation.rest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import oculus.aperture.common.rest.ApertureServerResource;

import com.oculusinfo.annotation.index.*;
import com.oculusinfo.annotation.*;
import com.oculusinfo.annotation.impl.*;
import com.oculusinfo.binning.*;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.ResourceException;

import com.google.inject.Inject;

public class AnnotationResource extends ApertureServerResource {


	private AnnotationService 			 _service;
	
	@Inject
	public AnnotationResource( AnnotationService service ) {
		_service = service;
	}
	

	@Post("json")
	public Representation postAnnotation( String jsonData ) throws ResourceException {

		try {

			JSONObject json = new JSONObject( jsonData );
			
			String requestType = json.getString( "type" ).toLowerCase();	
			JSONObject jsonResult = new JSONObject();
			
			if ( requestType.equals("write") ) {
				
				String layer = json.getString("layer");
				JSONObject data = json.getJSONObject("data");
				JSONAnnotation annotation = JSONAnnotation.fromJSON( data.getJSONObject("new") );			
				_service.writeAnnotation( layer, annotation );
				jsonResult.put("data", annotation.toJSON() );
				
			} else if ( requestType.equals("remove") ) {
	
				String layer = json.getString("layer");
				JSONObject data = json.getJSONObject("data");
				_service.removeAnnotation( layer, JSONAnnotation.fromJSON( data.getJSONObject("old") ) );
				
			} else if ( requestType.equals("modify") ) {
				
				String layer = json.getString("layer");
				JSONObject data = json.getJSONObject("data");
				JSONAnnotation oldAnnotation = JSONAnnotation.fromJSON( data.getJSONObject("old") );
				JSONAnnotation newAnnotation = JSONAnnotation.fromJSON( data.getJSONObject("new") );				
				_service.modifyAnnotation( layer, oldAnnotation, newAnnotation );				
				jsonResult.put("data", newAnnotation.toJSON() );
				
			} else if ( requestType.equals("filter") ) {
				
				String layer = json.getString("layer");
				JSONObject data = json.getJSONObject("data");
				JSONObject jsonFilters = data.getJSONObject("filters");
				UUID uuid = _service.configureFilters( layer, jsonFilters );
                jsonResult.put("uuid", uuid);

			} else if ( requestType.equals("list") ) {
				
                List<AnnotationInfo> layers = _service.listAnnotations();
                JSONArray jsonLayers = new JSONArray();
                for (int i=0; i<layers.size(); ++i) {
                    jsonLayers.put(i, layers.get(i).getRawData());
                }
                return new JsonRepresentation(jsonLayers);
			
			}
			
			setStatus(Status.SUCCESS_OK);		
			jsonResult.put("status", "success");
			return new JsonRepresentation(jsonResult);
			
		} catch (JSONException e) {
			throw new ResourceException(Status.CLIENT_ERROR_BAD_REQUEST,
					"Unable to create JSON object from supplied options string", e);
		} catch (IllegalArgumentException e) {
			throw new ResourceException(Status.SERVER_ERROR_INTERNAL,
					e.getMessage(), e);
		}
	}
	
	
	@Get
	public Representation getAnnotation() throws ResourceException {

		try {
			
			String id = (String) getRequest().getAttributes().get("id");
			String layer = (String) getRequest().getAttributes().get("layer");
			String levelDir = (String) getRequest().getAttributes().get("level");
			String xAttr = (String) getRequest().getAttributes().get("x");
			String yAttr = (String) getRequest().getAttributes().get("y");
			
			int zoomLevel = Integer.parseInt(levelDir);
			int x = Integer.parseInt(xAttr);
			int y = Integer.parseInt(yAttr);
			
			
			UUID uuid = null;
			if( !id.equals("default") ){
				uuid = UUID.fromString(id);
			}

		    // We return an object including the tile index ("index") and 
		    // the tile data ("data").
		    //
		    // The data should include index information, but it has to be 
		    // there for tiles with no data too, so we can't count on it.
			JSONObject result = new JSONObject();
		    JSONObject indexJson = new JSONObject();
		    indexJson.put("level", zoomLevel);
		    indexJson.put("xIndex", x);
		    indexJson.put("yIndex", y);
		    result.put("index", indexJson );
		    TileIndex index = new TileIndex( zoomLevel, x, y, AnnotationIndexer.NUM_BINS, AnnotationIndexer.NUM_BINS );
		    
		    Map<BinIndex, List<AnnotationData<?>>> data = _service.readAnnotations( uuid, layer, index );

		    JSONObject dataJson = new JSONObject();
		    for (Map.Entry<BinIndex, List<AnnotationData<?>>> entry : data.entrySet() ) {
				
		    	BinIndex binIndex = entry.getKey();
		    	List<AnnotationData<?>> annotations = entry.getValue();
		    	
		    	JSONArray annotationArray = new JSONArray();			    
			    for ( AnnotationData<?> annotation : annotations ) {
			    	annotationArray.put( annotation.toJSON() );
			    }
			    dataJson.put( binIndex.toString(), annotationArray );
		    }
		    
		    result.put( "annotationsByBin", dataJson );

		    setStatus(Status.SUCCESS_CREATED);
		    return new JsonRepresentation( result );

		} catch (Exception e){
			throw new ResourceException(Status.CLIENT_ERROR_BAD_REQUEST,
					"Unable to interpret requested tile from supplied URL.", e);
		}
	}
}
