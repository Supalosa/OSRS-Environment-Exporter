/*
Adapted from RuneLite source code, which provides this to us under the following
license:

BSD 2-Clause License

Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package cache.loaders;

import cache.IndexType;
import cache.definitions.ModelDefinition;
import cache.utils.ByteBufferExtKt;
import com.displee.cache.CacheLibrary;
import com.displee.cache.index.Index;
import com.displee.cache.index.archive.Archive;
import com.displee.cache.index.archive.file.File;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class ModelLoader
{
	private static final byte HAS_DELTA_X = 1;
	private static final byte HAS_DELTA_Y = 2;
	private static final byte HAS_DELTA_Z = 4;

	private final CacheLibrary cacheLibrary;
	private final Map<Integer, ModelDefinition> modelDefinitionCache;

	public ModelLoader(CacheLibrary cacheLibrary) {
		this.cacheLibrary = cacheLibrary;
		this.modelDefinitionCache = new HashMap<>();
	}

	public ModelDefinition get(int modelId) throws IOException
	{
		ModelDefinition def = modelDefinitionCache.get(modelId);
		if (def != null) return new ModelDefinition(def, false, false, false);

		Index index = cacheLibrary.index(IndexType.MODELS.getId());
		Archive archive = index.archive(modelId & 0xffff);
		if (archive == null) return null;

		File file = archive.getFiles().get(0);
		byte[] b = file.getData();
		archive.restore();  // Drop cached archive

		def = new ModelDefinition();
		def.setId(modelId);

		ByteBuffer stream = ByteBuffer.wrap(b);
		stream.position(stream.limit() - 2);

		switch (stream.getShort()) {
			case -3:
				decodeType3(def, stream);
				break;
			case -2:
				decodeType2(def, stream);
				break;
			case -1:
				decodeType1(def, stream);
				break;
			default:
				decodeOldFormat(def, stream);
		}

		def.computeNormals();
		def.computeTextureUVCoordinates();
		def.computeAnimationTables();
		modelDefinitionCache.put(modelId, def);

		return new ModelDefinition(def, false, false, false);
	}

	private void decodeType3(ModelDefinition def, ByteBuffer stream) {
		stream.position(stream.limit() - 26);
		readModelCommon(def, stream, false, true);
	}

	private void decodeType2(ModelDefinition def, ByteBuffer stream) {
		stream.position(stream.limit() - 23);
		readModelCommon(def, stream, true, true);
	}

	private void decodeType1(ModelDefinition def, ByteBuffer stream) {
		stream.position(stream.limit() - 23);
		readModelCommon(def, stream, false, false);
	}

	private void decodeOldFormat(ModelDefinition def, ByteBuffer stream) {
		stream.position(stream.limit() - 18);
		readModelCommon(def, stream, true, false);
	}

	private void readModelCommon(ModelDefinition def, ByteBuffer stream1, boolean isOldStyleTextures, boolean canHaveAnimayaGroups) {
		int vertexCount = ByteBufferExtKt.readUnsignedShort(stream1);
		int faceCount = ByteBufferExtKt.readUnsignedShort(stream1);
		int textureCount = ByteBufferExtKt.readUnsignedByte(stream1);
		int oldStyleIsTextured = isOldStyleTextures ? ByteBufferExtKt.readUnsignedByte(stream1) : 0;
		int hasFaceRenderTypes = !isOldStyleTextures ? ByteBufferExtKt.readUnsignedByte(stream1) : 0;
		int faceRenderPriority = ByteBufferExtKt.readUnsignedByte(stream1);
		int hasFaceTransparencies = ByteBufferExtKt.readUnsignedByte(stream1);
		int hasPackedTransparencyVertexGroups = ByteBufferExtKt.readUnsignedByte(stream1);
		int hasFaceTextures = !isOldStyleTextures ? ByteBufferExtKt.readUnsignedByte(stream1) : 0;
		int hasVertexSkins = ByteBufferExtKt.readUnsignedByte(stream1);
		int hasAnimayaGroups = canHaveAnimayaGroups ? ByteBufferExtKt.readUnsignedByte(stream1) : 0;

		def.setVertexCount(vertexCount);
		def.setFaceCount(faceCount);
		def.setTextureTriangleCount(textureCount);

		if (faceRenderPriority != 255) {
			def.setPriority((byte) faceRenderPriority);
		}

		stream1.rewind();
		if (textureCount > 0) {
			if (isOldStyleTextures) {
				def.setTextureRenderTypes(new byte[textureCount]);
			} else {
				def.setTextureRenderTypes(readByteArray(stream1, textureCount));
			}
		}

		final byte[] vertexFlags = readByteArray(stream1, vertexCount);

		if (hasFaceRenderTypes == 1) {
			def.setFaceRenderTypes(readByteArray(stream1, faceCount));
		}

		final byte[] faceIndexCompressionTypes = readByteArray(stream1, faceCount);

		if (faceRenderPriority == 255) {
			def.setFaceRenderPriorities(readByteArray(stream1, faceCount));
		}

		if (hasPackedTransparencyVertexGroups == 1) {
			readFaceSkins(def, stream1, faceCount);
		}

		byte[] faceTextureFlags = null;
		if (oldStyleIsTextured == 1) {
			faceTextureFlags = readByteArray(stream1, faceCount);
		}

		if (hasVertexSkins == 1) {
			readVertexSkins(def, stream1, vertexCount);
		}

		if (hasAnimayaGroups == 1 && !isOldStyleTextures) {
			readAnimayaGroups(stream1, vertexCount);
		}

		if (hasFaceTransparencies == 1) {
			def.setFaceAlphas(readByteArray(stream1, faceCount));
		}

		readFaceIndexData(def, stream1, faceIndexCompressionTypes);
		if (hasFaceTextures == 1) {
			readFaceTextures(def, stream1, faceCount);
			if (textureCount > 0) {
				readTextureCoordinates(def, stream1, faceCount, def.getFaceTextures());
			}
		}

		readFaceColors(def, stream1, faceCount);
		processFaceTextureFlags(def, faceTextureFlags, faceCount, oldStyleIsTextured);

		if (!isOldStyleTextures) {
			readVertexData(def, stream1, vertexFlags);
			readTextureTriangleVertexIndices(def, stream1, textureCount, false);
		} else {
			readTextureTriangleVertexIndices(def, stream1, textureCount, true);
			readVertexData(def, stream1, vertexFlags);
		}

		if (hasAnimayaGroups == 1 && isOldStyleTextures) {
			readAnimayaGroups(stream1, vertexCount);
		}

		discardUnusedTextures(def, faceCount, oldStyleIsTextured);
	}

	private void processFaceTextureFlags(ModelDefinition def, byte[] faceTextureFlags, int faceCount, int isTextured) {
		if (faceTextureFlags == null) return;

		final short[] faceColors = def.getFaceColors();

		final short[] faceTextures = new short[faceCount];
		final byte[] faceRenderTypes = new byte[faceCount];
		final byte[] textureCoordinates = new byte[faceCount];

		boolean usesFaceRenderTypes = false;
		boolean usesFaceTextures = false;

		for (int i = 0; i < faceCount; ++i) {
			int faceTextureFlag = faceTextureFlags[i] & 0xFF;
			if ((faceTextureFlag & 1) == 1) {
				faceRenderTypes[i] = 1;
				usesFaceRenderTypes = true;
			} else {
				faceRenderTypes[i] = 0;
			}

			if ((faceTextureFlag & 2) == 2) {
				textureCoordinates[i] = (byte) (faceTextureFlag >> 2);
				faceTextures[i] = faceColors[i];
				faceColors[i] = 127;
				if (faceTextures[i] != -1) {
					usesFaceTextures = true;
				}
			} else {
				textureCoordinates[i] = -1;
				faceTextures[i] = -1;
			}
		}

		if (usesFaceTextures) {
			def.setFaceTextures(faceTextures);
		}

		if (usesFaceRenderTypes) {
			def.setFaceRenderTypes(faceRenderTypes);
		}

		def.setTextureCoordinates(textureCoordinates);
	}

	private void discardUnusedTextures(ModelDefinition def, int faceCount, int isTextured) {
		if (isTextured != 1) return;

		boolean usesTextureCoords = false;
		final byte[] textureCoordinates = def.getTextureCoordinates();
		final int[] fvi1 = def.getFaceVertexIndices1();
		final int[] fvi2 = def.getFaceVertexIndices2();
		final int[] fvi3 = def.getFaceVertexIndices3();
		final short[] tvi1 = def.getTextureTriangleVertexIndices1();
		final short[] tvi2 = def.getTextureTriangleVertexIndices2();
		final short[] tvi3 = def.getTextureTriangleVertexIndices3();

		for (int i = 0; i < faceCount; ++i) {
			int coord = textureCoordinates[i] & 255;
			if (coord != 255) {
				if (fvi1[i] == (tvi1[coord] & 0xffff) && fvi2[i] == (tvi2[coord] & 0xffff) && fvi3[i] == (tvi3[coord] & 0xffff)) {
					textureCoordinates[i] = -1;
				} else {
					usesTextureCoords = true;
				}
			}
		}

		if (!usesTextureCoords) {
			def.setTextureCoordinates(null);
		}
	}

	private void readFaceIndexData(ModelDefinition def, ByteBuffer stream1, byte[] faceIndexCompressionTypes) {
		final int faceCount = faceIndexCompressionTypes.length;
		final int[] faceVertexIndices1 = new int[faceCount];
		final int[] faceVertexIndices2 = new int[faceCount];
		final int[] faceVertexIndices3 = new int[faceCount];
		int previousIndex1 = 0;
		int previousIndex2 = 0;
		int previousIndex3 = 0;

		for (int i = 0; i < faceCount; ++i)
		{
			int faceIndexCompressionType = faceIndexCompressionTypes[i];
			switch (faceIndexCompressionType) {
				case 1:
					previousIndex1 = ByteBufferExtKt.readShortSmart(stream1) + previousIndex3;
					previousIndex2 = ByteBufferExtKt.readShortSmart(stream1) + previousIndex1;
					previousIndex3 = ByteBufferExtKt.readShortSmart(stream1) + previousIndex2;
					break;
				case 2:
					previousIndex2 = previousIndex3;
					previousIndex3 = ByteBufferExtKt.readShortSmart(stream1) + previousIndex3;
					break;
				case 3:
					previousIndex1 = previousIndex3;
					previousIndex3 = ByteBufferExtKt.readShortSmart(stream1) + previousIndex3;
					break;
				case 4:
					int swap = previousIndex1;
					previousIndex1 = previousIndex2;
					previousIndex2 = swap;
					previousIndex3 = ByteBufferExtKt.readShortSmart(stream1) + previousIndex3;
					break;
			}
			faceVertexIndices1[i] = previousIndex1;
			faceVertexIndices2[i] = previousIndex2;
			faceVertexIndices3[i] = previousIndex3;
		}

		def.setFaceVertexIndices1(faceVertexIndices1);
		def.setFaceVertexIndices2(faceVertexIndices2);
		def.setFaceVertexIndices3(faceVertexIndices3);
	}

	private void readVertexData(ModelDefinition def, ByteBuffer stream, byte[] vertexFlags) {
		def.setVertexPositionsX(readVertexGroup(stream, vertexFlags, HAS_DELTA_X));
		def.setVertexPositionsY(readVertexGroup(stream, vertexFlags, HAS_DELTA_Y));
		def.setVertexPositionsZ(readVertexGroup(stream, vertexFlags, HAS_DELTA_Z));
	}

	private void readFaceColors(ModelDefinition def, ByteBuffer stream, int faceCount) {
		final short[] faceColors = new short[faceCount];
		stream.asShortBuffer().get(faceColors);
		stream.position(stream.position() + faceCount*2);
		def.setFaceColors(faceColors);
	}

	private void readFaceTextures(ModelDefinition def, ByteBuffer stream, int faceCount) {
		final short[] faceTextures = new short[faceCount];
		stream.asShortBuffer().get(faceTextures);
		stream.position(stream.position() + faceCount*2);

		for (int i = 0; i < faceCount; i++) {
			faceTextures[i]--;
		}

		def.setFaceTextures(faceTextures);
	}

	private void readVertexSkins(ModelDefinition def, ByteBuffer stream, int vertexCount) {
		final int[] vertexSkins = new int[vertexCount];
		for (int i = 0; i < vertexCount; ++i) {
			vertexSkins[i] = ByteBufferExtKt.readUnsignedByte(stream);
		}
		def.setVertexSkins(vertexSkins);
	}

	private int[] readVertexGroup(ByteBuffer stream, byte[] vertexFlags, byte deltaMask) {
		int position = 0;
		final int vertexCount = vertexFlags.length;
		final int[] vertices = new int[vertexCount];
		for (int i = 0; i < vertexCount; ++i) {
			if ((vertexFlags[i] & deltaMask) != 0) {
				position += ByteBufferExtKt.readShortSmart(stream);
			}
			vertices[i] = position;
		}
		return vertices;
	}
	
	private void readFaceSkins(ModelDefinition def, ByteBuffer stream, int faceCount) {
		final int[] faceSkins = new int[faceCount];
		for (int i = 0; i < faceCount; ++i) {
			faceSkins[i] = ByteBufferExtKt.readUnsignedByte(stream);
		}
		def.setFaceSkins(faceSkins);
	}

	private void readTextureCoordinates(ModelDefinition def, ByteBuffer stream, int faceCount, short[] faceTextures) {
		final byte[] textureCoordinates = new byte[faceCount];
		for (int i = 0; i < faceCount; ++i) {
			if (faceTextures[i] != -1) {
				textureCoordinates[i] = (byte) (ByteBufferExtKt.readUnsignedByte(stream) - 1);
			}
		}
		def.setTextureCoordinates(textureCoordinates);
	}

	private void readAnimayaGroups(ByteBuffer stream, int vertexCount) {
/*
		final int[][] animayaGroups = new int[vertexCount][];
		final int[][] animayaScales = new int[vertexCount][];

		for (int i = 0; i < vertexCount; ++i) {
			int animayaLength = ByteBufferExtKt.readUnsignedByte(stream);
			animayaGroups[i] = new int[animayaLength];
			animayaScales[i] = new int[animayaLength];

			for (int j = 0; j < animayaLength; ++j) {
				animayaGroups[i][j] = ByteBufferExtKt.readUnsignedByte(stream);
				animayaScales[i][j] = ByteBufferExtKt.readUnsignedByte(stream);
			}
		}

		def.setAnimayaGroups(animayaGroups);
		def.setAnimayaScales(animayaScales);
*/
		// Dummy implementation
		for (int i = 0; i < vertexCount; ++i) {
			int animayaLength = ByteBufferExtKt.readUnsignedByte(stream);
			stream.position(stream.position() + 2*animayaLength);
		}
	}

	private void readTextureTriangleVertexIndices(ModelDefinition def, ByteBuffer stream, int textureCount, boolean always) {
		final byte[] textureRenderTypes = def.getTextureRenderTypes();
		final short[] textureTriangleVertexIndices1 = new short[textureCount];
		final short[] textureTriangleVertexIndices2 = new short[textureCount];
		final short[] textureTriangleVertexIndices3 = new short[textureCount];
		for (int i = 0; i < textureCount; ++i) {
			if (always || (textureRenderTypes[i] & 255) == 0) {
				textureTriangleVertexIndices1[i] = stream.getShort();
				textureTriangleVertexIndices2[i] = stream.getShort();
				textureTriangleVertexIndices3[i] = stream.getShort();
			}
		}
		def.setTextureTriangleVertexIndices1(textureTriangleVertexIndices1);
		def.setTextureTriangleVertexIndices2(textureTriangleVertexIndices2);
		def.setTextureTriangleVertexIndices3(textureTriangleVertexIndices3);
	}

	private byte[] readByteArray(ByteBuffer stream, int length) {
		final byte[] array = new byte[length];
		stream.get(array);
		return array;
	}
}