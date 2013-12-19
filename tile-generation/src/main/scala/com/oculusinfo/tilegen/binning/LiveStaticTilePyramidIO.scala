/**
 * Copyright (c) 2013 Oculus Info Inc.
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



package com.oculusinfo.tilegen.binning



import java.io.InputStream
import java.io.IOException
import java.lang.{Iterable => JavaIterable}
import java.lang.{Double => JavaDouble}
import java.util.{List => JavaList}

import scala.collection.JavaConverters._
import scala.collection.mutable.MutableList
import scala.collection.mutable.{Map => MutableMap}

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD

import com.oculusinfo.binning.TileData
import com.oculusinfo.binning.TileIndex
import com.oculusinfo.binning.TilePyramid
import com.oculusinfo.binning.io.PyramidIO
import com.oculusinfo.binning.io.TileSerializer
import com.oculusinfo.binning.io.impl.DoubleAvroSerializer

import com.oculusinfo.tilegen.util.Rectangle
import com.oculusinfo.tilegen.spark.GeneralSparkConnector
import com.oculusinfo.tilegen.tiling.BinDescriptor
import com.oculusinfo.tilegen.tiling.RDDBinner
import com.oculusinfo.tilegen.tiling.StandardDoubleBinDescriptor
import com.oculusinfo.tilegen.tiling.TileMetaData




/**
 * This class reads and caches a data set for live queries of its tiles
 */
class LiveStaticTilePyramidIO (sc: SparkContext) extends PyramidIO {
  class TableData[BT, PT](val metaData: TileMetaData,
			  val pyramid: TilePyramid,
			  val data: RDD[(Double, Double, BT)],
			  val binDesc: BinDescriptor[BT, PT]) {}

  private val tables = MutableMap[String, TableData[_, _]]()


  def initializeForWrite (pyramidId: String): Unit = {
  }

  def writeTiles[T] (pyramidId: String,
                     tilePyramid: TilePyramid,
                     serializer: TileSerializer[T],
                     data: JavaIterable[TileData[T]]): Unit =
    throw new IOException("Can't write raw data")

  def writeMetaData (pyramidId: String,
                     metaData: String): Unit =
    throw new IOException("Can't write raw data")

  /**
   * Generate metadata for the given levels of the given dataset.
   * 
   * If we already have a metadata structure, just add those levels to it.
   */
  def initializeForRead (pyramidId: String,
                         tilePyramid: TilePyramid,
                         tileSize: Int): Unit = {
    if (!tables.contains(pyramidId)) {
      val data = sc.textFile(pyramidId).map(line => {
        val fields = line.split(',')

        (fields(0).toDouble, fields(1).toDouble, fields(2).toDouble)
      })
      initializeForRead(pyramidId, tilePyramid, tileSize, data, new StandardDoubleBinDescriptor)
    }
  }

  def initializeForRead[BT, PT] (pyramidId: String,
				 tilePyramid: TilePyramid,
				 tileSize: Int,
				 data: RDD[(Double, Double, BT)],
				 binDesc: BinDescriptor[BT, PT]) {
    if (!tables.contains(pyramidId)) {
      data.cache
      val fullBounds = tilePyramid.getTileBounds(new TileIndex(0, 0, 0, tileSize, tileSize))
      tables(pyramidId) =
	new TableData[BT, PT](new TileMetaData(pyramidId,
                                               "Live static tile level",
                                               tileSize,
                                               tilePyramid.getTileScheme(),
                                               tilePyramid.getProjection(),
                                               0,
                                               scala.Int.MaxValue,
                                               fullBounds,
                                               MutableList[(Int, String)](),
                                               MutableList[(Int, String)]()),
			      tilePyramid,
			      data,
			      binDesc)
    }
  }

  /*
   * Convert a set of tiles to testable bounds.
   *
   * The returned bounds are in two potential forms, paired into a 2-tuble.
   *
   * The first element is the bounds, in tile indices.
   */
  private def tilesToBounds (pyramid: TilePyramid,
                             tiles: Iterable[TileIndex]): Bounds = {
    var mutableRows = MutableList[Bounds]()
    val bounds = tiles.map(tile => 
      ((tile.getX, tile.getY()),
       new Bounds(tile.getLevel(),
                  new Rectangle[Int](tile.getX(), tile.getX(), tile.getY(), tile.getY()),
                  None))
    ).toSeq.sortBy(_._1).map(_._2).foreach(bounds => {
      if (mutableRows.isEmpty) {
        mutableRows += bounds
      } else {
        val last = mutableRows.last
        val combination = last union bounds
        if (combination.isEmpty) {
          mutableRows += bounds
        } else {
          mutableRows(mutableRows.size-1) = combination.get
        }
      }
    })

    val rows = mutableRows.foldRight(None: Option[Bounds])((bounds, rest) =>
      Some(new Bounds(bounds.level, bounds.indexBounds, rest))
    ).getOrElse(null)

    if (null == rows) {
      null
    } else {
      // reduce returns None if no reduction is required
      rows.reduce.getOrElse(rows)
    }
  }

  def readTiles[PT] (pyramidId: String,
                     serializer: TileSerializer[PT],
                     javaTiles: JavaIterable[TileIndex]): JavaList[TileData[PT]] = {
    def inner[BT: ClassManifest]: JavaList[TileData[PT]] = {
      val tiles = javaTiles.asScala
      if (!tables.contains(pyramidId) || 
          tiles.isEmpty) {
	null
      } else {
	val table = tables(pyramidId).asInstanceOf[TableData[BT, PT]]
	val metaData= table.metaData
	val pyramid = table.pyramid
	val data = table.data
	val binDesc = table.binDesc
	val bins = tiles.head.getXBins()

	val bounds = tilesToBounds(pyramid, tiles)

	val boundsTest = bounds.getSerializableContainmentTest(pyramid, bins)
	val spreaderFcn = bounds.getSpreaderFunction[BT](pyramid, bins);

	val binner = new RDDBinner
	binner.debug = true

	binner.processData(data, binDesc, spreaderFcn, bins).collect.toList.asJava
      }
    }

    inner
  }

  def getTileStream (pyramidId: String, tile: TileIndex): InputStream = {
    null
  }

  def readMetaData (pyramidId: String): String =
    tables.get(pyramidId).map(_.metaData.toString).getOrElse(null)

}
