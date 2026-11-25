package com.tvos.launcher

import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import kotlin.math.*

/** Samples only our own launcher artwork and video, not other apps' windows. */
internal class GlassBackdrop(private val image: View, private val video: android.view.TextureView,
    private val tint: View) {
    private var bitmap: Bitmap? = null
    private var stamp = 0L
    private val origin = IntArray(2)
    data class Frame(val bitmap: Bitmap, val x: Int, val y: Int, val width: Int, val height: Int)
    fun frame(): Frame? {
        val w=image.width; val h=image.height
        if(w<=0 || h<=0) return null
        val now=android.os.SystemClock.uptimeMillis()
        if(bitmap==null || now-stamp>200) {
            val bw=minOf(800,w)
            val bh=maxOf(1,(h.toFloat()*bw/w).roundToInt())
            val b=Bitmap.createBitmap(bw,bh,Bitmap.Config.ARGB_8888)
            val c=Canvas(b)
            c.drawColor(Color.rgb(14,19,27))
            c.save()
            c.scale(bw.toFloat()/w,bh.toFloat()/h)
            // Image is positioned in the launcher frame; include its actual bounds.
            c.translate(image.left.toFloat(),image.top.toFloat())
            image.draw(c)
            c.restore()
            if(video.alpha>.5f && video.isAvailable) {
                runCatching { video.getBitmap(bw,bh) }.getOrNull()?.let {
                    c.drawBitmap(it,0f,0f,null)
                }
            }
            c.save()
            c.scale(bw.toFloat()/w,bh.toFloat()/h)
            tint.draw(c)
            c.restore()
            bitmap=b
            stamp=now
        }
        image.getLocationOnScreen(origin)
        return Frame(bitmap ?: return null,origin[0],origin[1],w,h)
    }
    fun invalidate() { stamp=0L }
}

/** AGSL optical approximation on API 33+, sampled refractive mesh on older TVs. */
internal class LiquidGlassDrawable(private val owner:View, private val source:GlassBackdrop,
    private val radius:Float, private val focused:Boolean=false, private val dark:Boolean=false):Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path=Path()
    private val loc=IntArray(2)
    private val rect=RectF()
    private var shaderFailed=false
    private var optical: Any?=null
    private val nx=12
    private val ny=8
    private val mesh=FloatArray((nx+1)*(ny+1)*2)
    private var crop:Bitmap?=null
    private var previous:Bitmap?=null
    private var lastX=Int.MIN_VALUE
    private var lastY=Int.MIN_VALUE
    private val redraw=Runnable { invalidateSelf() }
    override fun draw(canvas:Canvas) {
        rect.set(bounds)
        val w=rect.width(); val h=rect.height()
        if(w<=0 || h<=0) return
        val r=minOf(radius,w/2,h/2)
        val f=source.frame()
        owner.getLocationOnScreen(loc)
        path.reset()
        path.addRoundRect(rect,r,r,Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        canvas.translate(rect.left,rect.top)
        paint.shader=null
        paint.style=Paint.Style.FILL
        paint.alpha=255
        if(f!=null) {
            val x=(loc[0]-f.x).toFloat()
            val y=(loc[1]-f.y).toFloat()
            var ok=false
            if(Build.VERSION.SDK_INT>=33 && !shaderFailed && canvas.isHardwareAccelerated) {
                ok=runCatching { drawAgsl(canvas,f,x,y,w,h,r); true }.getOrElse { shaderFailed=true; false }
            }
            if(!ok) drawMesh(canvas,f,x,y,w,h)
        } else {
            paint.color=0xff344352.toInt()
            canvas.drawRect(0f,0f,w,h,paint)
        }
        paint.shader=null
        paint.color=if(dark) 0x85101a28.toInt() else if(focused) 0x60ffffff else 0x248ac2ed
        canvas.drawRect(0f,0f,w,h,paint)
        paint.shader=LinearGradient(0f,0f,w,h,intArrayOf(0x64ffffff,0x12ffffff,0x03ffffff,0x2affffff),floatArrayOf(0f,.25f,.75f,1f),Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,w,h,paint)
        paint.shader=null
        canvas.restore()
        paint.style=Paint.Style.STROKE
        paint.strokeWidth=if(focused) 2f*owner.resources.displayMetrics.density else owner.resources.displayMetrics.density
        paint.shader=LinearGradient(rect.left,rect.top,rect.right,rect.bottom,intArrayOf(0xbaffffff.toInt(),0x20ffffff,0x66ffffff),null,Shader.TileMode.CLAMP)
        val inset=paint.strokeWidth/2
        val edge=RectF(rect).apply { inset(inset,inset) }
        canvas.drawRoundRect(edge,maxOf(0f,r-inset),maxOf(0f,r-inset),paint)
        paint.shader=null
        paint.strokeWidth=maxOf(1f,owner.resources.displayMetrics.density*.65f)
        paint.color=0x60000000
        val inner=RectF(edge).apply { inset(paint.strokeWidth*1.5f,paint.strokeWidth*1.5f) }
        canvas.drawRoundRect(inner,maxOf(0f,r-3*inset),maxOf(0f,r-3*inset),paint)
        paint.style=Paint.Style.FILL
        unscheduleSelf(redraw)
        if(owner.isShown) scheduleSelf(redraw,android.os.SystemClock.uptimeMillis()+220)
    }
    @android.annotation.TargetApi(33)
    private fun drawAgsl(c:Canvas,f:GlassBackdrop.Frame,x:Float,y:Float,w:Float,h:Float,r:Float) {
        val rs=(optical as? RuntimeShader) ?: RuntimeShader(AGSL).also { optical=it }
        val input=BitmapShader(f.bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
        rs.setInputShader("scene",input)
        rs.setFloatUniform("origin",x,y)
        rs.setFloatUniform("scale",f.bitmap.width.toFloat()/f.width,f.bitmap.height.toFloat()/f.height)
        rs.setFloatUniform("size",w,h)
        rs.setFloatUniform("radius",r)
        paint.shader=rs
        c.drawRect(0f,0f,w,h,paint)
        paint.shader=null
    }
    private fun drawMesh(c:Canvas,f:GlassBackdrop.Frame,x:Float,y:Float,w:Float,h:Float) {
        val factor=minOf(1f,360f/w,240f/h)
        val cw=maxOf(2,(w*factor).roundToInt())
        val ch=maxOf(2,(h*factor).roundToInt())
        val existing=crop
        if(previous!==f.bitmap || existing==null || existing.width!=cw || existing.height!=ch || lastX!=x.roundToInt() || lastY!=y.roundToInt()) {
            val b=Bitmap.createBitmap(cw,ch,Bitmap.Config.ARGB_8888)
            val sh=BitmapShader(f.bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
            val sx=f.width.toFloat()/f.bitmap.width
            val sy=f.height.toFloat()/f.bitmap.height
            sh.setLocalMatrix(Matrix().apply {
                setScale(sx*cw/w,sy*ch/h)
                postTranslate(-x*cw/w,-y*ch/h)
            })
            Canvas(b).drawRect(0f,0f,cw.toFloat(),ch.toFloat(),Paint(Paint.FILTER_BITMAP_FLAG).apply { shader=sh })
            // Sampled frosted glass, modest blur at the same aspect ratio.
            val softened=Bitmap.createScaledBitmap(b,maxOf(1,cw/3),maxOf(1,ch/3),true)
            crop=Bitmap.createScaledBitmap(softened,cw,ch,true)
            if(softened!==crop && softened!==b) softened.recycle()
            if(b!==crop) b.recycle()
            previous=f.bitmap
            lastX=x.roundToInt(); lastY=y.roundToInt()
        }
        val b=crop ?: return
        var i=0
        for(iy in 0..ny) for(ix in 0..nx) {
            val u=ix.toFloat()/nx; val v=iy.toFloat()/ny
            val dx=(u-.5f)*2f; val dy=(v-.5f)*2f
            val edge=maxOf(abs(dx),abs(dy)).pow(4)
            val bend=1f+.075f*edge
            mesh[i++]=(dx*bend*.5f+.5f)*w
            mesh[i++]=(dy*bend*.5f+.5f)*h
        }
        paint.shader=null
        paint.color=Color.WHITE
        c.drawBitmapMesh(b,nx,ny,mesh,0,null,0,paint)
    }
    override fun setAlpha(alpha:Int) { paint.alpha=alpha }
    override fun setColorFilter(colorFilter:ColorFilter?) { paint.colorFilter=colorFilter }
    @Suppress("DEPRECATION")
    override fun getOpacity():Int=PixelFormat.TRANSLUCENT

    companion object {
        private const val AGSL="""
            uniform shader scene;
            uniform float2 origin;
            uniform float2 scale;
            uniform float2 size;
            uniform float radius;
            half4 main(float2 p) {
                float2 q=p-size*0.5;
                float2 corner=abs(q)-(size*0.5-float2(radius));
                float d=length(max(corner,float2(0.0)))+min(max(corner.x,corner.y),0.0)-radius;
                float rim=1.0-smoothstep(0.0,max(3.0,radius*0.85),-d);
                float2 n=q/max(length(q),1.0);
                float2 uv=(origin+p-n*(rim*radius*0.33))*scale;
                float2 dispersion=n*rim*2.2*scale;
                float2 b=float2(1.5,1.5);
                half4 center=scene.eval(uv)*0.4+
                    (scene.eval(uv+b)+scene.eval(uv-b)+scene.eval(uv+float2(b.x,-b.y))+scene.eval(uv+float2(-b.x,b.y)))*0.15;
                half3 col=half3(mix(center.r,scene.eval(uv+dispersion).r,half(rim*0.45)),center.g,
                    mix(center.b,scene.eval(uv-dispersion).b,half(rim*0.45)));
                half shine=half(rim*(0.08+0.13*max(0.0,dot(n,float2(-0.7,-0.7)))));
                return half4(mix(col,half3(1.0),shine),1.0);
            }
        """
    }
}
