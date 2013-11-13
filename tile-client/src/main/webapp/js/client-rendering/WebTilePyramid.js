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

/* JSLint global declarations: these objects don't need to be declared. */
/*global OpenLayers */



/**
 * This module defines a TilePyramid class, the equivalent of 
 * WebMercatorTilePyramid in binning-utilities.
 */
define (function (require) {
    "use strict";



    var Class = require('../class'),
        WebMercatorTilePyramid,
        PI                       = Math.PI,
        EPSG_900913_SCALE_FACTOR = 20037508.342789244,
        DEGREES_TO_RADIANS       = PI / 180.0,
        // Factor for changing radians to degrees
        RADIANS_TO_DEGREES       = 180.0 / PI,
        rootToTileMercator, sinh, tileToLon, tileToLat;



    rootToTileMercator = function (lon, lat, level) {
        var latR = lat * DEGREES_TO_RADIANS,
            pow2 = 1 << level,
            x    = (lon + 180.0) / 360.0 * pow2,
            y    = (pow2 * (1 - Math.log(Math.tan(latR) + 1 / Math.cos(latR)) / PI)
                    / 2);
        return {x: x, y: pow2-y};
    };

    tileToLon = function (x, level) {
        var pow2 = 1 << level;
        return x / pow2 * 360.0 - 180.0;
    };

    sinh = function (arg) {
        return (Math.exp(arg) - Math.exp(-arg)) / 2.0;
    };

    tileToLat = function (y, level) {
        var pow2 = 1 << level,
            n    = -PI + (2.0 * PI * y) / pow2;
        return Math.atan(sinh(n)) * RADIANS_TO_DEGREES;
    };
            


    WebMercatorTilePyramid = Class.extend({
        getProjection: "EPSG:900913",
        getTileScheme: "TMS",

        getEPSG_900913Bounds: function (tile, bin) {
            var pow2          = 1 << tile.level,
                tileIncrement = 1.0/pow2,
                minX          = tile.xIndex * tileIncrement - 0.5,
                minY          = tile.yIndex * tileIncrement - 0.5,
                maxX, maxY, binXInc, binYInc;

            if (bin) {
                maxX = minX + tileIncrement;
                maxY = minY + tileIncrement;
            } else {
                binXInc = tileIncrement / tile.xBinCount;
                binYInc = tileIncrement / tile.yBinCount;
                minX = minX + bin.x * binXInc;
                minY = minY + (tile.yBinCount - bin.y - 1) * binYInc;
                maxX = minX + binXInc;
                maxY = minY + binYInc;
            }

            return {
                minX:    minX * 2.0 * EPSG_900913_SCALE_FACTOR,
                minY:    minY * 2.0 * EPSG_900913_SCALE_FACTOR,
                maxX:    maxX * 2.0 * EPSG_900913_SCALE_FACTOR,
                maxY:    maxY * 2.0 * EPSG_900913_SCALE_FACTOR,
                centerX: (minX + maxX) * EPSG_900913_SCALE_FACTOR,
                centerY: (minY + maxY) * EPSG_900913_SCALE_FACTOR,
                width:   (maxX - minX) * 2.0 * EPSG_900913_SCALE_FACTOR,
                height:  (maxY - minY) * 2.0 * EPSG_900913_SCALE_FACTOR
            };
        },

        rootToTile: function (lon, lat, level, bins) {
            if (!bins) {
                bins = 256;
            }

            var tileMercator = rootToTileMercator(lon, lat, level);

            return {
                level:     level,
                xIndex:    Math.floor(tileMercator.x),
                yIndex:    Math.floor(tileMercator.y),
                xBinCount: bins,
                yBinCount: bins
            };
        },

        rootToBin: function (x, y, tile) {
            var tileMercator = rootToTileMercator(x, y, tile.level);
            return {
                x: Math.floor((tileMercator.x - tile.xIndex) * tile.xBinCount),
                y: tile.yBinCount - 1 - Math.floor((tileMercator.y - tile.yIndex)
                                                  * tile.yBinCount)
            };
        },

        getTileBounds: function (tile) {
            var level = tile.level,
                north = tileToLat(tile.yIndex+1, level),
                south = tileToLat(tile.yIndex, level),
                east  = tileToLon(tile.xIndex+1, level),
                west  = tileToLon(tile.xIndex, level);

            return {
                minX:    west,
                minY:    south,
                maxX:    east,
                maxY:    north,
                centerX: (east+west)/2.0,
                centerY: (north+south)/2.0,
                width:   (east-west),
                height:  (north-south)
            };
        },

        getBinBounds: function (tile, bin) {
            var level   = tile.level,
                binXInc = 1.0 / tile.xBinCount,
                baseX   = tile.xIndex + bin.x * binXInc,
                binYInc = 1.0 / tile.yBinCount,
                baseY   = tile.yIndex + (tile.yBinCount - 1 - bin.y) * binYInc,
                north   = tileToLat(baseY + binYInc, level),
                south   = tileToLat(baseY, level),
                east    = tileToLon(baseX + binXInc, level),
                west    = tileToLon(baseX, level);

            return {
                minX:    west,
                minY:    south,
                maxX:    east,
                maxY:    north,
                centerX: (east+west)/2.0,
                centerY: (north+south)/2.0,
                width:   (east-west),
                height:  (north-south)
            };
        }
    });

    return WebMercatorTilePyramid;
});

