package org.project.engine;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.joml.Vector3f;

import java.nio.FloatBuffer;

public class ShaderProgram
{
    private final int programId;
    private int vertexShaderId;
    private int fragmentShaderId;

    public ShaderProgram() throws Exception
    {
        programId = GL30.glCreateProgram();
        if (programId == 0)
        {
            throw new Exception("Nu s-a putut crea programul de shadere!");
        }
    }

    public void createVertexShader(String code) throws Exception
    {
        vertexShaderId = createShader(code, GL30.GL_VERTEX_SHADER);
    }

    public void createFragmentShader(String code) throws Exception
    {
        fragmentShaderId = createShader(code, GL30.GL_FRAGMENT_SHADER);
    }

    private int createShader(String shaderCode, int shaderType) throws Exception
    {
        int shaderId = GL30.glCreateShader(shaderType);
        if (shaderId == 0) throw new Exception("Eroare la crearea shaderului!");
        GL30.glShaderSource(shaderId, shaderCode);
        GL30.glCompileShader(shaderId);
        if (GL30.glGetShaderi(shaderId, GL30.GL_COMPILE_STATUS) == 0)
        {
            throw new Exception("Eroare la compilarea shaderului: " + GL30.glGetShaderInfoLog(shaderId, 1024));
        }
        GL30.glAttachShader(programId, shaderId);
        return shaderId;
    }

    public void link() throws Exception
    {
        GL30.glLinkProgram(programId);
        if (GL30.glGetProgrami(programId, GL30.GL_LINK_STATUS) == 0)
        {
            throw new Exception("Eroare la link: " + GL30.glGetProgramInfoLog(programId, 1024));
        }
        if (vertexShaderId != 0) GL30.glDetachShader(programId, vertexShaderId);
        if (fragmentShaderId != 0) GL30.glDetachShader(programId, fragmentShaderId);
    }

    public void bind()
    {
        GL30.glUseProgram(programId);
    }

    public void unbind()
    {
        GL30.glUseProgram(0);
    }

    public void setUniform(String uniformName, Matrix4f value)
    {
        int uniformLocation = GL30.glGetUniformLocation(programId, uniformName);
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            FloatBuffer fb = stack.mallocFloat(16);
            value.get(fb);
            GL30.glUniformMatrix4fv(uniformLocation, false, fb);
        }
    }

    public void setUniform(String uniformName, Vector3f value)
    {
        int uniformLocation = GL30.glGetUniformLocation(programId, uniformName);
        GL30.glUniform3f(uniformLocation, value.x, value.y, value.z);
    }

    public void setUniform(String uniformName, int value) {
        int uniformLocation = GL30.glGetUniformLocation(programId, uniformName);
        GL30.glUniform1i(uniformLocation, value);
    }
}