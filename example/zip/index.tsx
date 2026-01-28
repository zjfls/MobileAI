
import React, { useState, useRef, useEffect, useCallback } from 'react';
import { createRoot } from 'react-dom/client';
import { 
  Pen, 
  Eraser, 
  Highlighter, 
  Layout, 
  BrainCircuit, 
  Download, 
  CloudUpload, 
  FilePlus, 
  Settings, 
  Trash2, 
  ChevronLeft, 
  ChevronRight,
  Palette,
  Sparkles,
  RefreshCw,
  Image as ImageIcon,
  CheckCircle2,
  XCircle
} from 'lucide-react';
import { GoogleGenAI, Type } from "@google/genai";

// --- Types ---
type Tool = 'pen' | 'highlighter' | 'eraser';
type Template = 'blank' | 'grid' | 'lines' | 'dots' | 'cornell';

interface Stroke {
  points: { x: number; y: number }[];
  color: string;
  width: number;
  tool: Tool;
}

interface Question {
  id: string;
  text: string;
  image?: string;
  yPosition: number;
}

interface Page {
  id: string;
  strokes: Stroke[];
  questions: Question[];
  template: Template;
  aiFeedback?: string;
}

// --- Constants ---
const COLORS = ['#000000', '#2563eb', '#dc2626', '#16a34a', '#9333ea', '#f59e0b'];
const PAPER_STYLES: Record<Template, string> = {
  blank: 'bg-white',
  grid: 'bg-white bg-[radial-gradient(#e5e7eb_1px,transparent_1px)] [background-size:20px_20px]',
  lines: 'bg-white bg-[linear-gradient(#e5e7eb_1px,transparent_1px)] [background-size:100%_24px]',
  dots: 'bg-white bg-[radial-gradient(#d1d5db_1px,transparent_1px)] [background-size:24px_24px]',
  cornell: 'bg-white border-l-[60px] border-slate-100',
};

// --- App Component ---
const App: React.FC = () => {
  const [pages, setPages] = useState<Page[]>([{ id: '1', strokes: [], questions: [], template: 'lines' }]);
  const [currentPageIndex, setCurrentPageIndex] = useState(0);
  const [currentTool, setCurrentTool] = useState<Tool>('pen');
  const [currentColor, setCurrentColor] = useState(COLORS[0]);
  const [isDrawing, setIsDrawing] = useState(false);
  const [isSidebarOpen, setIsSidebarOpen] = useState(true);
  const [isAiLoading, setIsAiLoading] = useState(false);
  const [hostUrl, setHostUrl] = useState('https://api.mock-edu.com/questions');
  
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const ctxRef = useRef<CanvasRenderingContext2D | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const currentPage = pages[currentPageIndex];

  // Initialize Canvas
  useEffect(() => {
    if (canvasRef.current) {
      const canvas = canvasRef.current;
      const rect = containerRef.current?.getBoundingClientRect();
      if (rect) {
        canvas.width = rect.width;
        canvas.height = Math.max(rect.height, 1200); // Minimum height for long notes
      }
      const ctx = canvas.getContext('2d');
      if (ctx) {
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
        ctxRef.current = ctx;
      }
      redraw();
    }
  }, [currentPageIndex]);

  // Handle Redrawing
  const redraw = useCallback(() => {
    const ctx = ctxRef.current;
    if (!ctx || !canvasRef.current) return;

    ctx.clearRect(0, 0, canvasRef.current.width, canvasRef.current.height);
    
    currentPage.strokes.forEach(stroke => {
      ctx.beginPath();
      ctx.strokeStyle = stroke.tool === 'eraser' ? '#ffffff' : stroke.color;
      ctx.lineWidth = stroke.width;
      ctx.globalAlpha = stroke.tool === 'highlighter' ? 0.3 : 1.0;
      
      if (stroke.points.length > 0) {
        ctx.moveTo(stroke.points[0].x, stroke.points[0].y);
        stroke.points.forEach(p => ctx.lineTo(p.x, p.y));
      }
      ctx.stroke();
    });
  }, [currentPage]);

  // Drawing Handlers
  const startDrawing = (e: React.MouseEvent | React.TouchEvent) => {
    setIsDrawing(true);
    const pos = getPointerPos(e);
    const newStroke: Stroke = {
      points: [pos],
      color: currentColor,
      width: currentTool === 'highlighter' ? 20 : currentTool === 'eraser' ? 30 : 2,
      tool: currentTool,
    };
    
    const updatedPages = [...pages];
    updatedPages[currentPageIndex].strokes.push(newStroke);
    setPages(updatedPages);
  };

  const draw = (e: React.MouseEvent | React.TouchEvent) => {
    if (!isDrawing) return;
    const pos = getPointerPos(e);
    
    const updatedPages = [...pages];
    const currentStrokes = updatedPages[currentPageIndex].strokes;
    const lastStroke = currentStrokes[currentStrokes.length - 1];
    lastStroke.points.push(pos);
    
    setPages(updatedPages);
    redraw();
  };

  const stopDrawing = () => {
    setIsDrawing(false);
  };

  const getPointerPos = (e: any) => {
    const canvas = canvasRef.current;
    if (!canvas) return { x: 0, y: 0 };
    const rect = canvas.getBoundingClientRect();
    const clientX = e.touches ? e.touches[0].clientX : e.clientX;
    const clientY = e.touches ? e.touches[0].clientY : e.clientY;
    return {
      x: clientX - rect.left,
      y: clientY - rect.top,
    };
  };

  // AI Features
  const generateAIExam = async () => {
    setIsAiLoading(true);
    try {
      const ai = new GoogleGenAI({ apiKey: process.env.API_KEY });
      const response = await ai.models.generateContent({
        model: "gemini-3-pro-preview",
        contents: "Generate 3 math or science problems suitable for a high school test. Return only the questions as a JSON array of strings.",
        config: {
          responseMimeType: "application/json",
          responseSchema: {
            type: Type.ARRAY,
            items: { type: Type.STRING }
          }
        }
      });

      const questionsData = JSON.parse(response.text);
      const newQuestions: Question[] = questionsData.map((q: string, idx: number) => ({
        id: Math.random().toString(),
        text: q,
        yPosition: 100 + (idx * 250)
      }));

      const updatedPages = [...pages];
      updatedPages[currentPageIndex].questions = [...updatedPages[currentPageIndex].questions, ...newQuestions];
      setPages(updatedPages);
    } catch (error) {
      console.error("AI Generation Error", error);
    } finally {
      setIsAiLoading(false);
    }
  };

  const solveWithAI = async () => {
    setIsAiLoading(true);
    try {
      const canvas = canvasRef.current;
      if (!canvas) return;
      
      const imageData = canvas.toDataURL('image/png').split(',')[1];
      const ai = new GoogleGenAI({ apiKey: process.env.API_KEY });
      
      const response = await ai.models.generateContent({
        model: "gemini-3-flash-preview",
        contents: [
          {
            inlineData: {
              mimeType: "image/png",
              data: imageData
            }
          },
          {
            text: "Look at the questions and the handwritten answers on this paper. Grade the answers and provide helpful explanations or corrections in Markdown format."
          }
        ]
      });

      const updatedPages = [...pages];
      updatedPages[currentPageIndex].aiFeedback = response.text;
      setPages(updatedPages);
    } catch (error) {
      console.error("AI Solving Error", error);
    } finally {
      setIsAiLoading(false);
    }
  };

  const fetchFromHost = () => {
    // Mocking host fetching
    const mockImageQ = {
      id: 'host-' + Date.now(),
      text: "Problem from Host Server:",
      image: "https://images.unsplash.com/photo-1635070041078-e363dbe005cb?auto=format&fit=crop&q=80&w=400",
      yPosition: 50
    };
    const updatedPages = [...pages];
    updatedPages[currentPageIndex].questions.push(mockImageQ);
    setPages(updatedPages);
  };

  const syncToHost = () => {
    alert("Syncing page data and handwriting vectors to host: " + hostUrl);
  };

  const addPage = () => {
    setPages([...pages, { id: Math.random().toString(), strokes: [], questions: [], template: 'lines' }]);
    setCurrentPageIndex(pages.length);
  };

  const deletePage = () => {
    if (pages.length === 1) return;
    const newPages = pages.filter((_, i) => i !== currentPageIndex);
    setPages(newPages);
    setCurrentPageIndex(Math.max(0, currentPageIndex - 1));
  };

  return (
    <div className="flex h-screen bg-[#f8fafc] text-slate-900 font-sans overflow-hidden">
      {/* Sidebar */}
      <aside className={`${isSidebarOpen ? 'w-72' : 'w-0'} bg-white border-r border-slate-200 transition-all duration-300 flex flex-col overflow-hidden`}>
        <div className="p-6 border-b border-slate-100 flex items-center gap-3">
          <div className="w-10 h-10 bg-indigo-600 rounded-xl flex items-center justify-center shadow-lg shadow-indigo-200">
            <Sparkles className="text-white w-6 h-6" />
          </div>
          <h1 className="text-xl font-bold tracking-tight text-slate-800">Aura Note</h1>
        </div>

        <div className="flex-1 overflow-y-auto p-4 space-y-6">
          <section>
            <h2 className="px-2 mb-3 text-xs font-semibold text-slate-400 uppercase tracking-wider">Notebooks</h2>
            <div className="space-y-1">
              {pages.map((page, idx) => (
                <button
                  key={page.id}
                  onClick={() => setCurrentPageIndex(idx)}
                  className={`w-full flex items-center gap-3 px-3 py-2.5 rounded-lg transition-all ${currentPageIndex === idx ? 'bg-indigo-50 text-indigo-700' : 'hover:bg-slate-50 text-slate-600'}`}
                >
                  <Layout size={18} />
                  <span className="text-sm font-medium">Page {idx + 1}</span>
                </button>
              ))}
              <button onClick={addPage} className="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-slate-400 hover:bg-slate-50 hover:text-slate-600 transition-all border-dashed border-2 border-slate-100 mt-2">
                <FilePlus size={18} />
                <span className="text-sm">Add New Page</span>
              </button>
            </div>
          </section>

          <section>
            <h2 className="px-2 mb-3 text-xs font-semibold text-slate-400 uppercase tracking-wider">Cloud Host</h2>
            <div className="bg-slate-50 p-3 rounded-xl space-y-3">
              <div className="flex flex-col gap-1">
                <label className="text-[10px] font-bold text-slate-400 ml-1">ENDPOINT</label>
                <input 
                  value={hostUrl} 
                  onChange={(e) => setHostUrl(e.target.value)}
                  className="text-xs bg-white border border-slate-200 rounded px-2 py-1 focus:outline-none focus:ring-1 focus:ring-indigo-500"
                />
              </div>
              <button onClick={fetchFromHost} className="w-full flex items-center justify-center gap-2 bg-white border border-slate-200 py-2 rounded-lg text-xs font-semibold text-slate-700 hover:bg-indigo-50 hover:border-indigo-200 transition-all">
                <Download size={14} /> Fetch Exercises
              </button>
              <button onClick={syncToHost} className="w-full flex items-center justify-center gap-2 bg-indigo-600 py-2 rounded-lg text-xs font-semibold text-white hover:bg-indigo-700 shadow-sm transition-all">
                <CloudUpload size={14} /> Sync to Host
              </button>
            </div>
          </section>
        </div>

        <div className="p-4 border-t border-slate-100 flex items-center justify-between">
          <button className="p-2 text-slate-400 hover:text-slate-600 rounded-lg hover:bg-slate-50 transition-colors">
            <Settings size={20} />
          </button>
          <button onClick={deletePage} className="p-2 text-slate-400 hover:text-red-500 rounded-lg hover:bg-red-50 transition-colors">
            <Trash2 size={20} />
          </button>
        </div>
      </aside>

      {/* Main Content */}
      <main className="flex-1 flex flex-col relative overflow-hidden">
        {/* Top Navigation */}
        <header className="h-16 bg-white/80 backdrop-blur-md border-b border-slate-200 px-6 flex items-center justify-between sticky top-0 z-10">
          <div className="flex items-center gap-4">
            <button 
              onClick={() => setIsSidebarOpen(!isSidebarOpen)}
              className="p-2 hover:bg-slate-100 rounded-lg transition-colors text-slate-500"
            >
              <Layout size={20} />
            </button>
            <div className="h-4 w-px bg-slate-200 mx-2" />
            <div className="flex items-center gap-1 text-sm font-semibold text-slate-600">
              <span>My Workbook</span>
              <ChevronRight size={14} className="text-slate-300" />
              <span className="text-slate-900">Calculus II - Week 4</span>
            </div>
          </div>

          <div className="flex items-center gap-3">
            <div className="flex bg-slate-100 p-1 rounded-xl">
              <button 
                onClick={() => setCurrentPageIndex(Math.max(0, currentPageIndex - 1))}
                className="p-1.5 hover:bg-white hover:shadow-sm rounded-lg transition-all"
              >
                <ChevronLeft size={18} />
              </button>
              <div className="px-3 flex items-center text-xs font-bold text-slate-500">
                {currentPageIndex + 1} / {pages.length}
              </div>
              <button 
                onClick={() => setCurrentPageIndex(Math.min(pages.length - 1, currentPageIndex + 1))}
                className="p-1.5 hover:bg-white hover:shadow-sm rounded-lg transition-all"
              >
                <ChevronRight size={18} />
              </button>
            </div>
            
            <button 
              onClick={generateAIExam}
              disabled={isAiLoading}
              className="flex items-center gap-2 bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 text-white px-4 py-2 rounded-xl text-sm font-bold shadow-lg shadow-indigo-100 transition-all"
            >
              {isAiLoading ? <RefreshCw className="animate-spin" size={16} /> : <BrainCircuit size={16} />}
              AI Generate Paper
            </button>
          </div>
        </header>

        {/* Toolbar - Floating */}
        <div className="absolute top-20 left-1/2 -translate-x-1/2 z-20 flex items-center gap-2 bg-white/90 backdrop-blur-xl border border-white/50 shadow-2xl shadow-slate-200 px-4 py-2.5 rounded-2xl">
          <div className="flex gap-1.5">
            <button 
              onClick={() => setCurrentTool('pen')}
              className={`p-2 rounded-xl transition-all ${currentTool === 'pen' ? 'bg-indigo-600 text-white shadow-md' : 'text-slate-500 hover:bg-slate-100'}`}
            >
              <Pen size={20} />
            </button>
            <button 
              onClick={() => setCurrentTool('highlighter')}
              className={`p-2 rounded-xl transition-all ${currentTool === 'highlighter' ? 'bg-yellow-400 text-white shadow-md' : 'text-slate-500 hover:bg-slate-100'}`}
            >
              <Highlighter size={20} />
            </button>
            <button 
              onClick={() => setCurrentTool('eraser')}
              className={`p-2 rounded-xl transition-all ${currentTool === 'eraser' ? 'bg-slate-800 text-white shadow-md' : 'text-slate-500 hover:bg-slate-100'}`}
            >
              <Eraser size={20} />
            </button>
          </div>

          <div className="h-6 w-px bg-slate-200 mx-2" />

          <div className="flex gap-1.5">
            {COLORS.map(color => (
              <button 
                key={color}
                onClick={() => { setCurrentColor(color); setCurrentTool('pen'); }}
                className={`w-7 h-7 rounded-full border-2 transition-transform hover:scale-110 active:scale-90 ${currentColor === color ? 'border-slate-800 ring-2 ring-slate-100' : 'border-transparent'}`}
                style={{ backgroundColor: color }}
              />
            ))}
          </div>

          <div className="h-6 w-px bg-slate-200 mx-2" />

          <button 
            onClick={() => {
              const templates: Template[] = ['blank', 'grid', 'lines', 'dots', 'cornell'];
              const currentIdx = templates.indexOf(currentPage.template);
              const nextTemplate = templates[(currentIdx + 1) % templates.length];
              const updated = [...pages];
              updated[currentPageIndex].template = nextTemplate;
              setPages(updated);
            }}
            className="flex items-center gap-2 px-3 py-1.5 rounded-lg text-slate-500 hover:bg-slate-100 text-xs font-bold transition-all uppercase tracking-tight"
          >
            <Palette size={16} />
            {currentPage.template}
          </button>
        </div>

        {/* Paper Workspace */}
        <div className="flex-1 overflow-auto p-12 flex flex-col items-center bg-[#f1f5f9]">
          <div 
            ref={containerRef}
            className={`relative min-h-[1200px] w-full max-w-[850px] shadow-2xl shadow-slate-300 rounded-sm mb-20 ${PAPER_STYLES[currentPage.template]} transition-colors duration-500`}
            style={{ 
              touchAction: 'none'
            }}
          >
            {/* Questions Layer */}
            <div className="absolute inset-0 pointer-events-none p-12 select-none">
              {currentPage.questions.map((q) => (
                <div 
                  key={q.id} 
                  className="absolute left-12 right-12 group bg-slate-50/50 p-4 rounded-xl border border-transparent hover:border-indigo-100 transition-all"
                  style={{ top: `${q.yPosition}px` }}
                >
                  <p className="text-slate-800 font-serif text-lg leading-relaxed mb-4">
                    {q.text}
                  </p>
                  {q.image && (
                    <img src={q.image} alt="Question" className="max-w-md rounded-lg shadow-sm mb-4 border border-slate-200" />
                  )}
                  <div className="w-full h-40 border-b border-dashed border-slate-200 mb-8" />
                </div>
              ))}
            </div>

            {/* Canvas Layer */}
            <canvas
              ref={canvasRef}
              onMouseDown={startDrawing}
              onMouseMove={draw}
              onMouseUp={stopDrawing}
              onMouseLeave={stopDrawing}
              onTouchStart={startDrawing}
              onTouchMove={draw}
              onTouchEnd={stopDrawing}
              className="absolute inset-0 cursor-crosshair"
            />
          </div>
        </div>

        {/* AI Action Panel - Floating Right */}
        <div className="absolute right-8 bottom-8 flex flex-col gap-3 z-30">
          {currentPage.aiFeedback && (
            <div className="mb-4 max-w-sm bg-white/95 backdrop-blur-xl p-6 rounded-3xl shadow-2xl border border-indigo-50 animate-in fade-in slide-in-from-bottom-4">
              <div className="flex items-center justify-between mb-3">
                <div className="flex items-center gap-2 text-indigo-600 font-bold text-sm">
                  <CheckCircle2 size={18} />
                  AI Grading Report
                </div>
                <button 
                  onClick={() => {
                    const updated = [...pages];
                    updated[currentPageIndex].aiFeedback = undefined;
                    setPages(updated);
                  }}
                  className="text-slate-400 hover:text-slate-600"
                >
                  <XCircle size={18} />
                </button>
              </div>
              <div className="prose prose-sm prose-slate max-h-[400px] overflow-y-auto">
                <p className="text-slate-600 leading-relaxed whitespace-pre-wrap text-sm">
                  {currentPage.aiFeedback}
                </p>
              </div>
            </div>
          )}

          <button 
            onClick={solveWithAI}
            disabled={isAiLoading}
            className="flex items-center gap-3 bg-white hover:bg-indigo-50 text-indigo-600 p-4 rounded-3xl shadow-xl shadow-slate-200/50 border border-indigo-100 font-bold transition-all hover:-translate-y-1 active:scale-95 disabled:opacity-50"
          >
            {isAiLoading ? (
              <RefreshCw className="animate-spin" size={24} />
            ) : (
              <>
                <div className="w-10 h-10 bg-indigo-600 rounded-2xl flex items-center justify-center text-white shadow-lg shadow-indigo-200">
                  <BrainCircuit size={20} />
                </div>
                <span>Analyze Handwriting</span>
              </>
            )}
          </button>
        </div>
      </main>
    </div>
  );
};

// --- Mount Point ---
const rootElement = document.getElementById('root');
if (rootElement) {
  createRoot(rootElement).render(<App />);
}
