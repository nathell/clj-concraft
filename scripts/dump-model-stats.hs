import NLP.Concraft.Polish.DAGSeg as Pol
import qualified NLP.Concraft.DAGSeg as C
import qualified NLP.Concraft.DAG.Guess as G
import qualified NLP.Concraft.DAG.DisambSeg as D
import qualified Data.CRF.Chain1.Constrained.DAG as CRF1
import qualified Data.CRF.Chain1.Constrained.Model as Md1
import qualified Data.CRF.Chain1.Constrained.Core as Core1
import qualified Data.CRF.Chain2.Tiers.DAG as CRF2
import qualified Data.CRF.Chain2.Tiers.Model as Md2
import qualified Data.Vector.Unboxed as U
import qualified Data.Vector as V
import qualified Data.Map as M
import qualified Data.Set as S
import qualified Control.Monad.Codec as Codec

main :: IO ()
main = do
  putStrLn "Loading model..."
  concraft <- Pol.loadModel Pol.simplify4gsr Pol.complexify4gsr Pol.simplify4dmb
    "concraft-pl-model-SGJP-20220221.gz"
  putStrLn "Model loaded."

  let tagset = C.tagset concraft
      guessNum = C.guessNum concraft
      gsr = C.guesser concraft
      seg = C.segmenter concraft
      dmb = C.disamber concraft

  putStrLn $ "guessNum: " ++ show guessNum

  -- Guesser stats
  putStrLn "\n--- Guesser ---"
  let gsrCrf = G.crf gsr
      gsrModel = CRF1.model gsrCrf
      gsrCodec = CRF1.codec gsrCrf
  putStrLn $ "  Values: " ++ show (U.length (Md1.values gsrModel))
  putStrLn $ "  ixMap: " ++ show (M.size (Md1.ixMap gsrModel))
  putStrLn $ "  r0: " ++ show (U.length (Core1.unAVec (Md1.r0 gsrModel)))
  putStrLn $ "  sgIxsV: " ++ show (U.length (Md1.sgIxsV gsrModel))
  putStrLn $ "  obIxsV: " ++ show (V.length (Md1.obIxsV gsrModel))
  putStrLn $ "  prevIxsV: " ++ show (V.length (Md1.prevIxsV gsrModel))
  putStrLn $ "  nextIxsV: " ++ show (V.length (Md1.nextIxsV gsrModel))
  putStrLn $ "  Ob codec to: " ++ show (M.size (Codec.to (fst gsrCodec)))
  putStrLn $ "  Ob codec from: " ++ show (length (Codec.from (fst gsrCodec)))
  putStrLn $ "  Label codec to: " ++ show (M.size (Codec.to (snd gsrCodec)))
  putStrLn $ "  Label codec from: " ++ show (length (Codec.from (snd gsrCodec)))

  -- Segmenter stats
  putStrLn "\n--- Segmenter ---"
  let segCrf = D.crf seg
      segModel = CRF2.model segCrf
  putStrLn $ "  Layers: " ++ show (CRF2.numOfLayers segCrf)
  putStrLn $ "  Values: " ++ show (U.length (Md2.values segModel))
  putStrLn $ "  FeatMap layers: " ++ show (V.length (Md2.featMap segModel))

  -- Disambiguator stats
  putStrLn "\n--- Disambiguator ---"
  let dmbCrf = D.crf dmb
      dmbModel = CRF2.model dmbCrf
  putStrLn $ "  Layers: " ++ show (CRF2.numOfLayers dmbCrf)
  putStrLn $ "  Values: " ++ show (U.length (Md2.values dmbModel))
  putStrLn $ "  FeatMap layers: " ++ show (V.length (Md2.featMap dmbModel))

  -- Print first 5 guesser values
  putStrLn $ "\n  First 5 guesser values: " ++ show (U.toList (U.take 5 (Md1.values gsrModel)))
