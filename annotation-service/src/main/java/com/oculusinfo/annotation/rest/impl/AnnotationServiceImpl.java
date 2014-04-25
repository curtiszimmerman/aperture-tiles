/*
 * Copyright (c) 2014 Oculus Info Inc. http://www.oculusinfo.com/
 * 
 * Released under the MIT License.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oculusinfo.annotation.rest.impl;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.locks.*;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Singleton;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.oculusinfo.annotation.*;
import com.oculusinfo.annotation.config.*;
import com.oculusinfo.annotation.io.*;
import com.oculusinfo.annotation.io.serialization.*;
import com.oculusinfo.annotation.io.serialization.impl.*;
import com.oculusinfo.annotation.index.*;
import com.oculusinfo.annotation.rest.*;
import com.oculusinfo.binning.*;
import com.oculusinfo.binning.io.*;
import com.oculusinfo.binning.io.serialization.TileSerializer;
import com.oculusinfo.binning.util.*;
import com.oculusinfo.factory.ConfigurableFactory;
import com.oculusinfo.factory.ConfigurationException;
import com.oculusinfo.tile.init.FactoryProvider;
import com.oculusinfo.tile.rendering.LayerConfiguration;


@Singleton
public class AnnotationServiceImpl implements AnnotationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnnotationServiceImpl.class);
      
    private List<AnnotationInfo>         _annotationLayers;
    private Map<String, AnnotationInfo>  _annotationLayersById;
    private ConcurrentHashMap< UUID, Map<String, Integer> > _filtersByUuid;
    
    private FactoryProvider<PyramidIO>         _pyramidIOFactoryProvider;
    private FactoryProvider<TileSerializer<?>> _tileSerializerFactoryProvider;
    private FactoryProvider<TilePyramid>       _tilePyramidFactoryProvider;
    
    
    protected AnnotationSerializer<AnnotationData<?>> _dataSerializer;
    protected AnnotationIndexer _indexer;
    protected AnnotationIO _io;
	
	protected final ReadWriteLock _lock = new ReentrantReadWriteLock();

	
	@Inject
    public AnnotationServiceImpl( @Named("com.oculusinfo.annotation.config") String annotationConfigurationLocation,
					    		  FactoryProvider<PyramidIO> pyramidIOFactoryProvider,
					    	      FactoryProvider<TileSerializer<?>> tileSerializerFactoryProvider,
					    		  FactoryProvider<TilePyramid> tilePyramidFactoryProvider,
					    		  AnnotationIndexer indexer,
					    		  AnnotationIO io,
    							  AnnotationSerializer<AnnotationData<?>> serializer ) {

		_pyramidIOFactoryProvider = pyramidIOFactoryProvider;
		_tileSerializerFactoryProvider = tileSerializerFactoryProvider;
		_tilePyramidFactoryProvider = tilePyramidFactoryProvider;
		_dataSerializer = serializer;	
		_filtersByUuid =  new ConcurrentHashMap<>();		
		_indexer = indexer;
		_io = io;
		
        readConfigFiles( getConfigurationFiles(annotationConfigurationLocation) );
    }

	
	/*
	@Inject
	public AnnotationServiceImpl( AnnotationIO io, AnnotationIndexer indexer ) {
		
		_tileSerializer = new StringLongPairArrayMapJSONSerializer();
		_dataSerializer = new JSONAnnotationDataSerializer();
		_uuidFilterMap =  new ConcurrentHashMap<>();
		_indexer = indexer;
		_io = io;
	}
	*/
	

	public void writeAnnotation( String layer, AnnotationData<?> annotation ) throws IllegalArgumentException {
		
		_lock.writeLock().lock();
    	try {
    		AnnotationConfiguration config = getAnnotationConfiguration(layer);
    		TilePyramid pyramid = config.produce(TilePyramid.class);
    		/* 
    		 * check in case client generated UUID results in IO collision, if so
    		 * prevent io corruption by throwing an exception, this is so statistically 
    		 * unlikely that any further action is unnecessary
    		 */ 
    		if ( checkForCollision( layer, annotation ) ) {
    			throw new IllegalArgumentException("UUID for data results in collision, WRITE operation aborted");
    		}
    		  		
    		addDataToTiles( layer, annotation, pyramid );
    		
    	} catch ( Exception e ) {
			e.printStackTrace();
    	} finally {
    		_lock.writeLock().unlock();
    	}

	}

	
	public void modifyAnnotation( String layer, 
								  AnnotationData<?> oldAnnotation, 
								  AnnotationData<?> newAnnotation ) throws IllegalArgumentException {
		
		// temporary naive modification, remove old, write new		
		_lock.writeLock().lock();
    	try {	
    		
    		/*
    		 *  ensure request is coherent with server state, if client is operating
    		 *  on a previous data state, prevent io corruption by throwing an exception
    		 */
    		if ( isRequestOutOfDate( layer, oldAnnotation ) ) {
    			throw new IllegalArgumentException("Client is out of sync with Server, "
    											 + "MODIFY operation aborted. It is recommended "
    											 + "upon receiving this exception to refresh all client annotations");        		
    		}
    		AnnotationConfiguration config = getAnnotationConfiguration(layer);
    		TilePyramid pyramid = config.produce(TilePyramid.class);
    		
			/* 
			 * Technically you should not have to re-tile the annotation if
			 * there is only a content change, as it will stay in the same tiles.
			 * However, we want to update the reference time-stamp in the containing 
			 * tile so that we can filter from tiles without relying on reading the 
			 * individual annotations themselves
			 */
			// remove from old tiles
			removeDataFromTiles( layer, oldAnnotation, pyramid );
			// add it to new tiles
			addDataToTiles( layer, newAnnotation, pyramid );
    	} catch ( Exception e ) {
			e.printStackTrace();
    	} finally {
    		_lock.writeLock().unlock();
    	}

	}
	
	

	public Map<BinIndex, List<AnnotationData<?>>> readAnnotations( UUID id, String layer, TileIndex query ) {
		
		Map<String, Integer> filters = null;		
		/*
		 * If user has specified a filter, use it, otherwise pull all annotations in tile 
		 */
		if ( id != null ) {
			filters = _filtersByUuid.get( id );
		}
		
		_lock.readLock().lock();
    	try {
    		AnnotationConfiguration config = getAnnotationConfiguration(layer);
    		TilePyramid pyramid = config.produce(TilePyramid.class);
			    		
    		return getDataFromTiles( layer, query, filters, pyramid );
    		
    	} catch ( Exception e ) {
			e.printStackTrace();
    	} finally { 		
    		_lock.readLock().unlock();
    	}
    	
    	return null;
	}
	
		
	public void removeAnnotation( String layer, AnnotationData<?> annotation ) throws IllegalArgumentException {
		
		TilePyramid pyramid;
		try {
			AnnotationConfiguration config = getAnnotationConfiguration(layer);
			pyramid = config.produce(TilePyramid.class);
			
			_lock.writeLock().lock();		
			try {
				
				/*
	    		 *  ensure request is coherent with server state, if client is operating
	    		 *  on a previous data state, prevent io corruption by throwing an exception
	    		 */
	    		if ( isRequestOutOfDate( layer, annotation ) ) {
	    			throw new IllegalArgumentException("Client is out of sync with Server, "
													 + "REMOVE operation aborted. It is recommended "
													 + "upon receiving this exception to refresh all client annotations");       		
	    		}
				// remove the references from tiles
				removeDataFromTiles( layer, annotation, pyramid );
				// remove data from io
				removeDataFromIO( layer, annotation.getReference() );
				
			} finally {
				_lock.writeLock().unlock();
			}
			
		} catch ( Exception e ) {
			e.printStackTrace();
		}
	}


	@Override
	public List<AnnotationInfo> listAnnotations () {
	    return _annotationLayers;
	}
	

	public AnnotationConfiguration getAnnotationConfiguration( String layer ) {
					
		try {
			AnnotationConfiguration configFactory = new AnnotationConfiguration( _pyramidIOFactoryProvider,
					_tileSerializerFactoryProvider,
					_tilePyramidFactoryProvider, null, Collections.singletonList("config") );
			
			configFactory.readConfiguration( _annotationLayersById.get(layer).getRawData() );		
			return configFactory.produce(AnnotationConfiguration.class);
			
		} catch (ConfigurationException e) {
	        LOGGER.warn("Error configuring annotatons for {}", layer, e);
	        return null;
	    }
		

	}

	@Override
	public UUID configureFilters (String layerId, Map<String, Integer> filters ) {

		
        UUID uuid = UUID.randomUUID();
        _filtersByUuid.put( uuid, filters );

        return uuid;
	}
	
	/*
	@Override
	public UUID configureAnnotationLayer (String layerId, JSONObject configuration) {
        
		AnnotationInfo info = new AnnotationInfo( configuration );
		JSONObject jsonFilters = info.getFilterConfiguration();
	
		Map<String, Integer> filters = new HashMap<>();	
		Iterator<?> priorities = jsonFilters.keys();
        while( priorities.hasNext() ) {
        	
        	String priority = (String)priorities.next();		            
            int count = jsonFilters.getInt( priority );
            filters.put( priority, count );
        }
		
        UUID uuid = UUID.randomUUID();
        _configurationsById.put( layerId, configuration );
        _filtersByUuid.put( uuid, filters );

        return uuid;
	}
	*/
	
	
	// ////////////////////////////////////////////////////////////////////////
	// Section: Configuration reading methods
	//
	private File[] getConfigurationFiles (String location) {
    	try {
	    	// Find our configuration file.
	    	URI path = null;
	    	if (location.startsWith("res://")) {
	    		location = location.substring(6);
	    		path = AnnotationServiceImpl.class.getResource(location).toURI();
	    	} else {
	    		path = new File(location).toURI();
	    	}

	    	File configRoot = new File(path);
	    	if (!configRoot.exists())
	    		throw new Exception(location+" doesn't exist");

	    	if (configRoot.isDirectory()) {
	    		return configRoot.listFiles();
	    	} else {
	    		return new File[] {configRoot};
	    	}
    	} catch (Exception e) {
        	LOGGER.warn("Can't find configuration file {}", location, e);
        	return new File[0];
		}
    }

    private void readConfigFiles (File[] files) {
		for (File file: files) {
			try {
			    JSONObject contents = new JSONObject(new JSONTokener(new FileReader(file)));
			    JSONArray configurations = contents.getJSONArray("layers");
    			for (int i=0; i<configurations.length(); ++i) {    				
    				AnnotationInfo info = new AnnotationInfo(configurations.getJSONObject(i));
    				addConfiguration(info);
    			}
	    	} catch (FileNotFoundException e) {
	    		LOGGER.error("Cannot find annotation configuration file {} ", file, e);
	    		return;
	    	} catch (JSONException e) {
	    		LOGGER.error("Annotation configuration file {} was not valid JSON.", file, e);
	    	}
		}
    }
	
	private void addConfiguration (AnnotationInfo info) {
    	_annotationLayers.add(info);
    	_annotationLayersById.put(info.getID(), info);
    }
	
	
	
	
	/*
	 * 
	 * Helper methods
	 * 
	 */	

	/*
	 * Check data UUID in IO, if already exists, return true
	 */
	private boolean checkForCollision( String layer, AnnotationData<?> annotation ) {
		
		List<Pair<String,Long>> reference = new LinkedList<>();
		reference.add( annotation.getReference() );
		return ( readDataFromIO( layer, reference ).size() > 0 ) ;

	}
	
	/*
	 * Check data timestamp from clients source, if out of date, return true
	 */
	public boolean isRequestOutOfDate( String layer, AnnotationData<?> annotation ) {
		
		List<Pair<String, Long>> reference = new LinkedList<>();
		reference.add( annotation.getReference() );
		List<AnnotationData<?>> annotations = readDataFromIO( layer, reference );
		
		if ( annotations.size() == 0 ) {
			// removed since client update, abort
			return true;
		}
		
		if ( !annotations.get(0).getTimeStamp().equals( annotation.getTimeStamp() ) ) {
			// clients timestamp doesn't not match most up to date, abort
			return true;
		}
		
		// everything seems to be in order
		return false;
	}
	
	
	/*
	 * Iterate through all indices, find matching tiles and add data reference, if tile
	 * is missing, add it
	 */
	private void addDataReferenceToTiles( List< TileData<Map<String, List<Pair<String,Long>>>>> tiles, List<TileAndBinIndices> indices, AnnotationData<?> data ) {		
		
    	for ( TileAndBinIndices index : indices ) {			
			// check all existing tiles for matching index
    		boolean found = false;
			for ( TileData<Map<String, List<Pair<String,Long>>>> tile : tiles ) {				
				if ( tile.getDefinition().equals( index.getTile() ) ) {
					// tile exists already, add data to bin
					AnnotationManipulator.addDataToTile( tile, index.getBin(), data );
					found = true;
					break;
				} 
			}
			if ( !found ) {
				// no tile exists, add tile
				TileData<Map<String, List<Pair<String,Long>>>> tile = new TileData<>( index.getTile() );				
				AnnotationManipulator.addDataToTile( tile, index.getBin(), data );
				tiles.add( tile );    	
			}
		}				
	}
	
	/*
	 * Iterate through all tiles, removing data reference from bins, any tiles with no bin entries
	 * are added to tileToRemove, the rest are added to tilesToWrite
	 */
	private void removeDataReferenceFromTiles( List<TileData<Map<String, List<Pair<String,Long>>>>> tilesToWrite, 
											   List<TileIndex> tilesToRemove, 
											   List<TileData<Map<String, List<Pair<String,Long>>>>> tiles, 
											   AnnotationData<?> data,
											   TilePyramid pyramid ) {		
		
		// clear supplied lists
		tilesToWrite.clear();
		tilesToRemove.clear();	

		// for each tile, remove data from bins
		for ( TileData<Map<String, List<Pair<String,Long>>>> tile : tiles ) {				
			// get bin index for the annotation in this tile
			BinIndex binIndex = _indexer.getIndex( data, tile.getDefinition().getLevel(), pyramid ).getBin();		
			// remove data from tile
			AnnotationManipulator.removeDataFromTile( tile, binIndex, data );				
		}	
		
		// determine which tiles need to be re-written and which need to be removed
		for ( TileData<Map<String, List<Pair<String,Long>>>> tile : tiles ) {
			if ( AnnotationManipulator.isTileEmpty( tile ) ) {				
				// if no data left, flag tile for removal
				tilesToRemove.add( tile.getDefinition() );
			} else {
				// flag tile to be written
				tilesToWrite.add( tile );
			}
		}
	}
	
	/*
	 * convert a List<TileAndBinIndices> to List<TileIndex>
	 */
	private List<TileIndex> convert( List<TileAndBinIndices> tiles ) {
		
		List<TileIndex> indices = new ArrayList<>();
		for ( TileAndBinIndices tile : tiles ) {			
			indices.add( tile.getTile() );
		}
		
		return indices;
	}

	
	private Map<BinIndex, List<AnnotationData<?>>> getDataFromTiles( String layer, TileIndex tileIndex, Map<String, Integer> filter, TilePyramid pyramid ) {
		
		// wrap index into list 
		List<TileIndex> indices = new LinkedList<>();
		indices.add( tileIndex );
			
		// get tiles
		List<TileData<Map<String,List<Pair<String,Long>>>>> tiles = readTilesFromIO( layer, indices );
				
		// for each tile, assemble list of all data references
		List<Pair<String,Long>> references = new LinkedList<>();
		for ( TileData<Map<String,List<Pair<String,Long>>>> tile : tiles ) {					
			if ( filter != null ) {
				// filter provided
				references.addAll( AnnotationManipulator.getFilteredReferencesFromTile( tile, filter ) );
			} else {
				// no filter provided
				references.addAll(  AnnotationManipulator.getAllReferencesFromTile( tile ) );
			}
		}
		
		// read data from io in bulk
		List<AnnotationData<?>> data = readDataFromIO( layer, references );

		// assemble data by bin
		Map<BinIndex, List<AnnotationData<?>>> dataByBin =  new HashMap<>();
		for ( AnnotationData<?> d : data ) {
			// get index 
			BinIndex binIndex = _indexer.getIndex( d, tileIndex.getLevel(), pyramid ).getBin();
			if (!dataByBin.containsKey( binIndex)) {
				// no data under this bin, add list to map
				dataByBin.put( binIndex, new LinkedList<AnnotationData<?>>() );
			}
			// add data to list, under bin
			dataByBin.get( binIndex ).add( d );
		}
		return dataByBin;
	}

	
	private void addDataToTiles( String layer, AnnotationData<?> data, TilePyramid pyramid ) {
		
		// get list of the indices for all levels
		List<TileAndBinIndices> indices = _indexer.getIndices( data, pyramid );
		// get all affected tiles
		List<TileData<Map<String, List<Pair<String,Long>>>>> tiles = readTilesFromIO( layer, convert( indices ) );
		// add new data reference to tiles
    	addDataReferenceToTiles( tiles, indices, data );
		// write tiles back to io
		writeTilesToIO( layer, tiles );    		
		// write data to io
		writeDataToIO( layer, data );

	}
	
	
	private void removeDataFromTiles( String layer, AnnotationData<?> data, TilePyramid pyramid ) {
		
		// get list of the indices for all levels
    	List<TileAndBinIndices> indices = _indexer.getIndices( data, pyramid );	    	
		// read existing tiles
		List<TileData<Map<String, List<Pair<String,Long>>>>> tiles = readTilesFromIO( layer, convert( indices ) );					
		// maintain lists of what bins to modify and what bins to remove
		List<TileData<Map<String, List<Pair<String,Long>>>>> tilesToWrite = new LinkedList<>(); 
		List<TileIndex> tilesToRemove = new LinkedList<>();			
		// remove data from tiles and organize into lists to write and remove
		removeDataReferenceFromTiles( tilesToWrite, tilesToRemove, tiles, data, pyramid );
		// write modified tiles
		writeTilesToIO( layer, tilesToWrite );		
		// remove empty tiles and data
		removeTilesFromIO( layer, tilesToRemove );
	}
	
	
	private String getDataLayerId( String layer ) {
		return layer + ".data";
	}
	
	protected void writeTilesToIO( String layer, List<TileData<Map<String, List<Pair<String,Long>>>>> tiles ) {
		
		if ( tiles.size() == 0 ) return;
		
		try {
			AnnotationConfiguration config = getAnnotationConfiguration(layer);
			PyramidIO io = config.produce(PyramidIO.class);	
			TileSerializer<Map<String, List<Pair<String,Long>>>> serializer = config.produce(TileSerializer.class);

			io.initializeForWrite( layer );
			io.writeTiles( layer, (TilePyramid)null, serializer, tiles );
					
		} catch ( Exception e ) {
			e.printStackTrace();
		}
		
	}
	
	
	protected void writeDataToIO( String layer, AnnotationData<?> data ) {
		
		List<AnnotationData<?>> dataList = new LinkedList<>();
		dataList.add( data );
		
		String dataLayer = getDataLayerId( layer );
		
		try {
			
			_io.initializeForWrite( dataLayer );		
			_io.writeData( dataLayer, _dataSerializer, dataList );

		} catch ( Exception e ) {
			e.printStackTrace();
		}
	}
	

	protected void removeTilesFromIO( String layer, List<TileIndex> tiles ) {

		if ( tiles.size() == 0 ) return;
		
		try {		
			AnnotationConfiguration config = getAnnotationConfiguration(layer);
			PyramidIO io = config.produce(PyramidIO.class);			
			io.removeTiles( layer, tiles );	
			
		} catch ( Exception e ) {
			e.printStackTrace();
		}

	}
	
	
	protected void removeDataFromIO( String layer, Pair<String, Long> data ) {
		
		List<Pair<String, Long>> dataList = new LinkedList<>();
		dataList.add( data );
		
		String dataLayer = getDataLayerId( layer );
				
		try {

			_io.removeData( dataLayer, dataList );											
			
		} catch ( Exception e ) {
			e.printStackTrace();
		}

	}
	
	
	protected List<TileData<Map<String, List<Pair<String,Long>>>>> readTilesFromIO( String layer, List<TileIndex> indices ) {
			
		List<TileData<Map<String, List<Pair<String,Long>>>>> tiles = new LinkedList<>();
		
		if ( indices.size() == 0 ) return tiles;
		
		try {
			AnnotationConfiguration config = getAnnotationConfiguration(layer);
			PyramidIO io = config.produce(PyramidIO.class);	
			TileSerializer<Map<String, List<Pair<String,Long>>>> serializer = config.produce(TileSerializer.class);

			io.initializeForRead( layer, 0, 0, null );		
			tiles = io.readTiles( layer, serializer, indices );						
					
		} catch ( Exception e ) {
			e.printStackTrace();
		}

		return tiles;		
	}
	
	protected List<AnnotationData<?>> readDataFromIO( String layer, List<Pair<String,Long>> references ) {
		
		List<AnnotationData<?>> data = new LinkedList<>();
		
		if ( references.size() == 0 ) return data;
		
		String dataLayer = getDataLayerId( layer );		
		
		try {

			_io.initializeForRead( dataLayer );	
			data = _io.readData( dataLayer, _dataSerializer, references );			
			
		} catch ( Exception e ) {
			e.printStackTrace();
		}

		return data;
	}
	
}