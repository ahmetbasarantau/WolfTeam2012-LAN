----------------------------------------------------------
local _szCaption = GetStr('szCaption') or 'ApexProtect'
local _szMainText	= GetStr('szText1') or 'ApexProtect 反外挂引擎 3.4_110410'
local _szButtonOk = GetStr('szBt1') or '确定'
local _szHyperLink = GetStr('szText2') or '立即访问http://www.apex.com'
local _addrHyperLink = GetStr('szText2Link') or'http://www.apex.com'


----------------------------------------------------------

local canvas = {
	w = 450,	--窗口宽
	h = 230		--窗口高
}

local button = {}
local res = {}


--画带阴影的字体
local function drawshadowstring(g,x,y,t,fmt,sz,color)
	if fmt then
		DrawStringFmt(g,x,y,t,sz or 8,color or 0xff111111)
		DrawStringFmt(g,x-1,y-1,t,sz or 8)
	else
		DrawString(g,x,y,t,sz or 8,color or 0xff111111)
		DrawString(g,x-1,y-1,t,sz or 8)
	end
end

--判断点在矩形里
local function ptinrect(self,x,y)
	return x > self.x and
			y > self.y and
			x < self.x + self.w and
			y < self.y + self.h and true or nil
end

--创建按钮
local function createbutton(x,y,txt,cb,w,h,rw,rh,brush_on,brush_off)
	local button = {
		x = x,
		y = y,
		w = w or 90,
		h = h or 24,
		rw = rw or 4,
		rh = rh or 4,
		cb = cb,	--	回调
		brush = brush_on or res.brush_button,
		brush_on = brush_off or res.brush_button_on,
		text = txt,	--	字体
		draw = function (self,g)
				local Brush = (self.mouse_on and self.brush_on ) or self.brush
				DrawRoundRect(g,Brush,self.x,self.y,self.w,self.h,self.rw,self.rh)
			  drawshadowstring(g,self.x+self.w/2,self.y+self.h/2-6,self.text,true)
		end,
		mouseon = ptinrect
	}
	return button
end



--创建超链接
local function createhyperlink(x,y,txt,cb,w,h,rw,rh,brush_on,brush_off)
	local button = {
		x = x,
		y = y,
		w = w or 90,
		h = h or 24,
		rw = rw or 4,
		rh = rh or 4,
		cb = cb,	--	回调
		brush = brush_on or res.brush_button,
		brush_on = brush_off or res.brush_button_on,
		text = txt,	--	字体
		draw = function (self,g)
			local Color = (self.mouse_on and  -1 ) or 0xffdddddd
			local Style = (self.mouse_on and  0 ) or 4
			DrawString(g,self.x,self.y,txt,10, Color,0,Style)
		end,
		mouseon = ptinrect
	}
	return button
end


local function createcross(x,y,cb,brush_on,brush_off)
	local button = {
		x = x,
		y = y,
		w =13,
		h =  13,
		rw =  1,
		rh =  1,
		cb = cb,	--	回调
		brush = brush_on or res.brush_button,
		brush_on = brush_off or res.brush_button_on,
		text = txt,	--	字体
		draw = function (self,g)
			local Brush =  self.brush_on
			DrawRoundRect(g,Brush,self.x,self.y,self.w,self.h,self.rw,self.rh)
			local CrossColor = self.mouse_on and  0xff777777 or 0xff343434
			DrawLine(g,self.x,self.y,self.x+self.w-2,self.y+self.h-2,CrossColor,1)
			DrawLine(g,self.x+self.w-2,self.y,self.x,self.y+self.h-2,CrossColor,1)
			--DrawLine(,0xff7b9A18)
		end,
		mouseon = ptinrect
	}
	return button
end

local ok_cb	--确定按钮回调
local link_cb	--超链接回调
local close_cb	--右上角关闭按钮之回调

--初始化窗口
function wnd_init(hwnd)
	SetWindowPos(
		hwnd,
		( screen.w - canvas.w ) / 2,		--窗口X
		( screen.h - canvas.h ) / 2,		--窗口Y
		450,						--窗口W
		218 )						--窗口H
	SetWindowText(hwnd,_szCaption)
	res.img_logo = LoadImage('logo.png')	--加载logo图片
	res.img_ApexProtect = LoadImage('apex_protect.png')	--加载字体图片
	res.brush_button = CreateBrush(0x55,0x55,0x55)	--创建话刷 用于画按钮
	res.brush_button_on = CreateBrush(156,170,123)	--创建话刷 用于画按钮
	button.ok = createbutton(180,(450-90)/2,_szButtonOk,ok_cb)	--确定按钮
	--button.link = createhyperlink(140,98,_szHyperLink,link_cb,190,24)	--超链接
	button.close = createcross(canvas.w-16,3,close_cb)
end


local last_state
function wnd_event(x,y,msg,hwnd)
--[[
	0	WM_SETCURSOR
	1	WM_LBUTTONUP
	2	WM_LBUTTONUP
--]]
	local Bt

	for k,v in pairs(button) do
		if v:mouseon(x,y) then
			Bt = v
			v.mouse_on = true
			break
		else
			v.mouse_on = false
		end
	end

	if Bt then cursor_hand = Bt else cursor_hand = nil end

	if last_state ~= cursor_hand then
		Bt = Bt or last_state
		Fresh(hwnd,Bt.x,Bt.y,Bt.w,Bt.h)
	end

	if msg == 1 then
		if Bt and Bt.cb then
			Bt.cb()
		end
	end

	last_state = cursor_hand
end

function wnd_paint(g)
	FillRect(g,0,0,canvas.w,canvas.h,0xff343434)	--背景 x,y,w,h,color
	FillRect(g,0,0,canvas.w,18,0xff555555)	--标题栏 x,y,w,h,color
	DrawImage(g,res.img_logo,15,20)	--logo图片
	DrawImage(g,res.img_ApexProtect,150,30)	--ApexProtect 字体图片
	DrawLine(g,20,canvas.h-60,canvas.w-20,canvas.h-60,0xff7b9A18)	--分割线

	drawshadowstring(g,7,3,_szCaption)
	--_szMainText = SetLineMaxSize(_szMainText,10 );
	DrawString(g,140,70,_szMainText,12,0xffffffff,0,1)


	for k,v in pairs(button) do
		v:draw(g)
	end
end

function wnd_destroy()
end

ok_cb = function ()
	DestroyWindow(1)
end

close_cb = function ()
	DestroyWindow(1)
end


link_cb = function ()
		OpenWeb('http://www.test.com')
		DestroyWindow(1)
end

